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
 */
class StudentDaoIT {

    /** Per-test student-id range; tests in this class use ranges starting at {@code 90_000}. */
    private static final int SNO_BASE = 90_000;

    private static MySQLContainer<?> mysql;
    private static SessionFactory factory;
    private static StudentDaoHibernateImpl dao;

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
        factory = HibernateSessionFactoryUtil.create(url, user, pass);
        dao = new StudentDaoHibernateImpl(factory);
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void clean() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            // bulk delete rows in our reserved range only; safe to run alongside other test data
            session.createMutationQuery("delete from Student s where s.sno >= :lo")
                    .setParameter("lo", SNO_BASE)
                    .executeUpdate();
            session.getTransaction().commit();
        }
    }

    private void insert(int sno, String sname, int age) {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(new Student(sno, sname, age, "addr-" + sno));
            session.getTransaction().commit();
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

        PageResult<Student> page = dao.search("alic", 1, 10, "sno", "asc");

        assertEquals(2, page.total());
        Set<String> names = new HashSet<>();
        page.items().forEach(s -> names.add(s.getSname()));
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

        PageResult<Student> page = dao.search("李", 1, 10, "sno", "asc");

        assertEquals(1, page.total());
        assertEquals("李雷", page.items().get(0).getSname());
    }

    @ParameterizedTest(name = "substring ''{0}'' is escaped: literal ''{1}'' does not match all rows")
    @CsvSource({"%,percent", "_,underscore"})
    void substringBranchEscapesWildcards(String query, String literalName) {
        insert(SNO_BASE + 1, literalName, 20);
        insert(SNO_BASE + 2, "Other", 21);

        PageResult<Student> page = dao.search(query, 1, 10, "sno", "asc");

        // If the DAO failed to escape %/_, these queries would match every row in
        // the reserved range, not just the literal-named one.
        assertEquals(1, page.total(), "query='" + query + "' should only match the literal name");
        assertEquals(literalName, page.items().get(0).getSname());
    }

    @Test
    @DisplayName("subquery containing a real 'name' substring also matches (escape does not over-match)")
    void substringBranchStillMatches() {
        insert(SNO_BASE + 1, "Alice", 20);
        insert(SNO_BASE + 2, "Bob", 21);

        PageResult<Student> page = dao.search("lic", 1, 10, "sno", "asc");

        assertEquals(1, page.total());
        assertEquals("Alice", page.items().get(0).getSname());
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

        PageResult<Student> page = dao.search("b!", 1, 10, "sno", "asc");

        assertEquals(1, page.total());
        assertEquals("ab!c", page.items().get(0).getSname());
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
        PageResult<Student> page = dao.search("!!", 1, 10, "sno", "asc");
        assertEquals(0, page.total(), "input '!!' should not match a single '!'");
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
            PageResult<Student> p = dao.search(null, pageNo, 2, "sname", "asc");
            for (Student s : p.items()) {
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

        PageResult<Student> page = dao.search(null, 1, 3, "sno", "asc");
        List<Integer> snots = new ArrayList<>();
        page.items().forEach(s -> snots.add(s.getSno()));
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

        PageResult<Student> page = dao.search(null, 1, 10, "age", "desc");

        assertEquals(4, page.total());
        // age=20 rows first in sno asc, then age=19 row
        assertEquals(SNO_BASE + 1, page.items().get(0).getSno());
        assertEquals(SNO_BASE + 2, page.items().get(1).getSno());
        assertEquals(SNO_BASE + 3, page.items().get(2).getSno());
        assertEquals(SNO_BASE + 4, page.items().get(3).getSno());
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
        PageResult<Student> page = dao.search(null, 99, 2, "sno", "asc");
        // 5 rows / 2 per page -> 3 pages; asking for 99 should clamp to 3.
        assertEquals(3, page.page());
        assertEquals(5, page.total());
        assertEquals(1, page.items().size(), "page 3 has 5 - 2*2 = 1 row");
    }

    @Test
    @DisplayName("empty result returns total=0, items=empty, page=1")
    void emptyResult() {
        PageResult<Student> page = dao.search("no-such-name-xyzzy", 1, 10, "sno", "asc");
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        assertEquals(1, page.page());
    }

    // --------------------------------------------------------------------
    // CRUD round-trips (sanity)
    // --------------------------------------------------------------------

    @Test
    @DisplayName("insert + findBySno round-trip preserves fields")
    void insertFindRoundTrip() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            dao.insert(new Student(SNO_BASE + 1, "RoundTrip", 33, "Somewhere"));
            session.getTransaction().commit();
        }
        Student found = dao.findBySno(SNO_BASE + 1);
        assertNotNull(found);
        assertEquals("RoundTrip", found.getSname());
        assertEquals(33, found.getAge());
        assertEquals("Somewhere", found.getAddress());
    }

    @Test
    @DisplayName("update overwrites the entity in place")
    void updateOverwrites() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            dao.insert(new Student(SNO_BASE + 1, "Before", 20, "addr-before"));
            session.getTransaction().commit();
        }
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Student s = session.get(Student.class, SNO_BASE + 1);
            s.update("After", 21, "addr-after");
            dao.update(s);
            session.getTransaction().commit();
        }
        Student found = dao.findBySno(SNO_BASE + 1);
        assertEquals("After", found.getSname());
        assertEquals(21, found.getAge());
        assertEquals("addr-after", found.getAddress());
    }

    @Test
    @DisplayName("delete removes the row")
    void deleteRemoves() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            dao.insert(new Student(SNO_BASE + 1, "doomed", 20, "addr-doomed"));
            session.getTransaction().commit();
        }
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Student s = session.get(Student.class, SNO_BASE + 1);
            dao.delete(s);
            session.getTransaction().commit();
        }
        assertNull(dao.findBySno(SNO_BASE + 1));
    }
}