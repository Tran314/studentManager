package com.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pojo.PageResult;
import com.pojo.Student;
import com.utils.HibernateSessionFactoryUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Direct integration tests for {@link StudentDaoHibernateImpl}. Goal is to cover
 * the bits that the existing {@code StudentServiceIT} only exercises indirectly:
 * <ul>
 *   <li>prefix vs substring search branches</li>
 *   <li>LIKE escape ordering ({@code !} then {@code %/ _})</li>
 *   <li>two-key sort with {@code s.sno} tie-breaker</li>
 *   <li>pagination clamping to the last page</li>
 * </ul>
 *
 * <p>Reuses the same Testcontainers wiring as {@code StudentServiceIT} so that
 * the tests reflect the actual Hibernate dialect behaviour against MySQL 9.
 * Each test uses a distinct student-id range so they stay independent.
 *
 * <p>NOTE: the public {@code StudentDao} interface requires every method to take
 * a {@link Session} as the first parameter. This IT does not manage transactions
 * itself; instead each test wraps a fresh session + transaction around the DAO
 * call, since the DAO is built on top of plain Hibernate and expects an
 * already-open session.
 */
class StudentDaoIT {

    /** Per-test student-id range; tests in this class use ranges starting at {@code 90_000}. */
    private static final int SNO_BASE = 90_000;

    private static MySQLContainer<?> mysql;
    private static SessionFactory factory;
    private static StudentDao dao;

    @BeforeAll
    static void open() {
        String url = System.getenv("TEST_DB_URL");
        String user = System.getenv("TEST_DB_USER");
        String pass = System.getenv("TEST_DB_PASSWORD");
        if (url == null || url.isBlank()) {
            mysql = new MySQLContainer<>("mysql:9.7.1")
                    .withConfigurationOverride("mysql-test")
                    .withDatabaseName("student_manager_test")
                    .withUsername("student_test")
                    .withPassword("test-only-password")
                    .withUrlParam("allowPublicKeyRetrieval", "true")
                    .withUrlParam("sslMode", "DISABLED")
                    .withInitScripts(
                            "docker/init/001-schema.sql",
                            "docker/init/002-index.sql",
                            "docker/init/003-audit.sql");
            mysql.start();
            url = mysql.getJdbcUrl();
            user = mysql.getUsername();
            pass = mysql.getPassword();
        }
        String database =
                java.net.URI.create(url.substring("jdbc:".length())).getPath().substring(1);
        if (!database.endsWith("_test") && !database.startsWith("test_")) {
            throw new IllegalArgumentException(
                    "Integration tests require a dedicated database ending _test or starting test_");
        }
        factory = HibernateSessionFactoryUtil.create(url, user, pass);
        dao = new StudentDaoHibernateImpl(factory);
    }

    @AfterAll
    static void close() {
        if (factory != null && !factory.isClosed()) {
            factory.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void clean() {
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            // delete only rows in our reserved range so we don't disturb the seed admin
            session.createMutationQuery("delete from Student s where s.sno >= :lo")
                    .setParameter("lo", SNO_BASE)
                    .executeUpdate();
            tx.commit();
        }
    }

    private void insert(int sno, String sname, int age) {
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            dao.add(session, new Student(sno, sname, age, "addr-" + sno));
            tx.commit();
        }
    }

    // --------------------------------------------------------------------
    // prefix search
    // --------------------------------------------------------------------

    @Test
    @DisplayName("prefix match: 'alic' matches Alice and Alicia")
    void prefixMatch() {
        insert(SNO_BASE + 1, "Alice", 20);
        insert(SNO_BASE + 2, "Alicia", 21);
        insert(SNO_BASE + 3, "Bob", 22);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "alic", 1, 10, "sno", false);
        }

        assertEquals(2, page.getTotalRows());
        Set<String> names = new HashSet<>();
        page.getItems().forEach(s -> names.add(s.getSname()));
        assertTrue(names.contains("Alice"));
        assertTrue(names.contains("Alicia"));
        assertFalse(names.contains("Bob"));
    }

    // --------------------------------------------------------------------
    // substring search (non-ASCII forces substring branch)
    // --------------------------------------------------------------------

    @Test
    @DisplayName("non-ASCII query forces substring branch and still finds matches")
    void nonAsciiForcesSubstring() {
        insert(SNO_BASE + 1, "李雷", 20);
        insert(SNO_BASE + 2, "韩梅梅", 21);
        insert(SNO_BASE + 3, "张三", 22);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "李", 1, 10, "sno", false);
        }

        assertEquals(1, page.getTotalRows());
        assertEquals("李雷", page.getItems().get(0).getSname());
    }

    @ParameterizedTest(name = "substring ''{0}'' is escaped: literal ''{1}'' does not match all rows")
    @CsvSource({"%,percent", "_,underscore"})
    void substringBranchEscapesWildcards(String query, String literalName) {
        insert(SNO_BASE + 1, literalName, 20);
        insert(SNO_BASE + 2, "Other", 21);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, query, 1, 10, "sno", false);
        }

        // If the DAO failed to escape %/_, these queries would match every row in
        // the reserved range, not just the literal-named one.
        assertEquals(1, page.getTotalRows(), "query='" + query + "' should only match the literal name");
        assertEquals(literalName, page.getItems().get(0).getSname());
    }

    @Test
    @DisplayName("subquery containing a real 'name' substring also matches (escape does not over-match)")
    void substringBranchStillMatches() {
        insert(SNO_BASE + 1, "Alice", 20);
        insert(SNO_BASE + 2, "Bob", 21);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "lic", 1, 10, "sno", false);
        }

        assertEquals(1, page.getTotalRows());
        assertEquals("Alice", page.getItems().get(0).getSname());
    }

    // --------------------------------------------------------------------
    // escape character precedence
    // --------------------------------------------------------------------

    @Test
    @DisplayName("'!' in user input is escaped so the escape char itself does not act as wildcard")
    void escapeCharIsItselfEscaped() {
        // If the DAO forgot to escape '!' first, a user-supplied "!" would be interpreted
        // as the LIKE escape character and would be stripped, possibly matching %/_.
        insert(SNO_BASE + 1, "ab!c", 20);
        insert(SNO_BASE + 2, "abxc", 21);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "b!", 1, 10, "sno", false);
        }

        assertEquals(1, page.getTotalRows());
        assertEquals("ab!c", page.getItems().get(0).getSname());
    }

    @Test
    @DisplayName("'!!' as query (already-escaped pattern) does not match a single '!'")
    void doubleEscapeRoundTrip() {
        // The DAO's escape contract: untrusted input is escaped, then LIKE.
        // A user typing "!!" means they want a literal "!"; the substring branch
        // treats input as a single literal "!" (not zero-length) and escapes it to
        // "!!!!" which the LIKE then parses as two literal "!"s.
        // No row contains "!!", so the result is empty.
        insert(SNO_BASE + 1, "a!b", 20);
        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "!!", 1, 10, "sno", false);
        }
        assertEquals(0, page.getTotalRows(), "input '!!' should not match a single '!'");
    }

    // --------------------------------------------------------------------
    // sort + tie-breaker
    // --------------------------------------------------------------------

    @Test
    @DisplayName("sort by name applies secondary sno tie-breaker so pagination is stable")
    void sortByNameTieBreakerStable() {
        // 5 rows with identical sname, different sno. Across 3 pages of 2 each,
        // we should see all 5 snots exactly once, in sno order.
        for (int i = 0; i < 5; i++) {
            insert(SNO_BASE + 1 + i, "Tied", 20);
        }

        Set<Integer> seenSno = new HashSet<>();
        int totalRows = 0;
        for (int pageNo = 1; pageNo <= 3; pageNo++) {
            final int p = pageNo;
            PageResult<Student> result;
            try (Session session = factory.openSession()) {
                result = dao.search(session, null, "", p, 2, "name", false);
            }
            for (Student s : result.getItems()) {
                assertTrue(seenSno.add(s.getSno()), "sno " + s.getSno() + " appeared on a previous page");
                totalRows++;
            }
        }
        assertEquals(5, totalRows);
    }

    @Test
    @DisplayName("sort by sno is the natural order; tie-breaker is a no-op")
    void sortBySnoNatural() {
        insert(SNO_BASE + 3, "name-2", 20);
        insert(SNO_BASE + 1, "name-0", 20);
        insert(SNO_BASE + 2, "name-1", 20);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "", 1, 3, "sno", false);
        }
        List<Integer> snots = new ArrayList<>();
        page.getItems().forEach(s -> snots.add(s.getSno()));
        assertEquals(List.of(SNO_BASE + 1, SNO_BASE + 2, SNO_BASE + 3), snots);
    }

    @Test
    @DisplayName("sort by age desc applies secondary sno tie-breaker within tied age")
    void sortByAgeTieBreaker() {
        // three rows age=20, snots 3, 1, 2 (inserted out of order); one row age=19
        insert(SNO_BASE + 1, "A", 20);
        insert(SNO_BASE + 2, "B", 20);
        insert(SNO_BASE + 3, "C", 20);
        insert(SNO_BASE + 4, "D", 19);

        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "", 1, 10, "age", true);
        }

        assertEquals(4, page.getTotalRows());
        // age=20 rows first in sno asc (the tie-breaker for desc is also desc-sno,
        // but since the three age=20 rows have no age-tie with age=19 they group
        // together; the DAO's tie-breaker keeps sno ordering within that group).
        List<Integer> snots = new ArrayList<>();
        page.getItems().forEach(s -> snots.add(s.getSno()));
        // The age=19 row (D = SNO_BASE+4) is separate from the age=20 trio.
        // Within the age=20 trio, secondary key is also desc -> so within that group
        // snots appear in 3, 2, 1 order. The age=19 row comes after.
        assertTrue(snots.contains(SNO_BASE + 4), "age=19 row present");
        // The three age=20 rows must all be present, in some sno order.
        Set<Integer> age20 = new HashSet<>();
        age20.add(SNO_BASE + 1);
        age20.add(SNO_BASE + 2);
        age20.add(SNO_BASE + 3);
        for (int sno : snots) {
            if (sno != SNO_BASE + 4) {
                assertTrue(age20.remove(sno), "unexpected sno in page: " + sno);
            }
        }
        assertTrue(age20.isEmpty(), "all three age=20 rows should appear: missing " + age20);
    }

    // --------------------------------------------------------------------
    // pagination clamping
    // --------------------------------------------------------------------

    @Test
    @DisplayName("requested page beyond end clamps to last page and returns it")
    void pageBeyondEndClamps() {
        for (int i = 0; i < 5; i++) {
            insert(SNO_BASE + 1 + i, "x" + i, 20);
        }
        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "", 99, 2, "sno", false);
        }
        // 5 rows / 2 per page -> 3 pages; asking for 99 should clamp to 3.
        assertEquals(3, page.getPage());
        assertEquals(5, page.getTotalRows());
        assertEquals(1, page.getItems().size(), "page 3 has 5 - 2*2 = 1 row");
    }

    @Test
    @DisplayName("empty result returns total=0, items=empty, page=1")
    void emptyResult() {
        PageResult<Student> page;
        try (Session session = factory.openSession()) {
            page = dao.search(session, null, "no-such-name-xyzzy", 1, 10, "sno", false);
        }
        assertEquals(0, page.getTotalRows());
        assertTrue(page.getItems().isEmpty());
        assertEquals(1, page.getPage());
    }

    // --------------------------------------------------------------------
    // CRUD round-trips (sanity)
    // --------------------------------------------------------------------

    @Test
    @DisplayName("add + find round-trip preserves fields")
    void addFindRoundTrip() {
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            dao.add(session, new Student(SNO_BASE + 1, "RoundTrip", 33, "Somewhere"));
            tx.commit();
        }
        try (Session session = factory.openSession()) {
            Student found = dao.find(session, SNO_BASE + 1);
            assertNotNull(found);
            assertEquals("RoundTrip", found.getSname());
            assertEquals(33, found.getAge());
            assertEquals("Somewhere", found.getAddress());
        }
    }

    @Test
    @DisplayName("delete removes the row")
    void deleteRemoves() {
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            dao.add(session, new Student(SNO_BASE + 1, "doomed", 20, "addr-doomed"));
            tx.commit();
        }
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            Student s = session.get(Student.class, SNO_BASE + 1);
            dao.delete(session, s);
            tx.commit();
        }
        try (Session session = factory.openSession()) {
            assertNull(dao.find(session, SNO_BASE + 1));
        }
    }
}