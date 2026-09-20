package com.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pojo.Identity;
import com.pojo.PageResult;
import com.pojo.Student;
import com.utils.HibernateSessionFactoryUtil;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

/**
 * Integration tests against a real MySQL. Uses Testcontainers when no
 * {@code TEST_DB_URL} is in the environment, otherwise connects to the
 * MySQL supplied by {@code compose.test.yaml}. Each test uses a distinct
 * student-id range so the tests stay independent and may be reordered.
 */
class StudentServiceIT {

    private static final String TEST_PASSWORD = "Student-test-2026";
    private static final Identity TEST_ADMIN = new Identity(0L, "test-admin", "ADMIN", null, 0);

    private static MySQLContainer<?> mysql;
    private static SessionFactory factory;
    private static StudentServiceImpl service;

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
                            "docker/init/001-schema.sql", "docker/init/002-index.sql", "docker/init/003-audit.sql");
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
        service = new StudentServiceImpl(factory);
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
    void clearFixture() {
        try (Session s = factory.openSession()) {
            var tx = s.beginTransaction();
            for (String table : List.of("audit_log", "account", "student", "app_seed")) {
                s.createNativeMutationQuery("delete from " + table).executeUpdate();
            }
            tx.commit();
        }
    }

    @Test
    void seedCreatesTenStudentsThenRegisterAddsOne() {
        service.seed("Admin-test-2026", TEST_PASSWORD);
        assertEquals(10, service.search(null, "", 1, 10, "sno", false).totalRows());
        service.register(11, "张三_<测试>%", TEST_PASSWORD, 21, "香港😀", TEST_ADMIN);
        assertEquals("张三_<测试>%", service.find(11).getSname());
    }

    @Test
    void searchByNameAndPageBoundary() {
        service.register(100, "alice", TEST_PASSWORD, 20, "addr-a", TEST_ADMIN);
        service.register(101, "alex", TEST_PASSWORD, 21, "addr-b", TEST_ADMIN);
        service.register(102, "bob", TEST_PASSWORD, 22, "addr-c", TEST_ADMIN);

        assertEquals(2, service.search(null, "al", 1, 10, "sno", false).totalRows());
        PageResult<Student> empty = service.search(null, "missing", 1, 10, "sno", false);
        assertEquals(0, empty.totalRows());
        assertEquals(1, empty.page());
        // Out-of-range page snaps to last page, never to zero.
        PageResult<Student> over = service.search(null, "", 999, 10, "sno", false);
        assertTrue(over.page() >= 1);
    }

    @Test
    void wildcardCharactersAreTreatedLiterally() {
        service.register(200, "foo_bar", TEST_PASSWORD, 20, "", TEST_ADMIN);
        service.register(201, "50%off", TEST_PASSWORD, 20, "", TEST_ADMIN);
        // Underscore and percent in user input match literal characters, not SQL wildcards.
        assertEquals(1, service.search(null, "_", 1, 10, "sno", false).totalRows());
        assertEquals(1, service.search(null, "%", 1, 10, "sno", false).totalRows());
    }

    @Test
    void chineseNamesUseSubstringMatch() {
        // Review fix C-6: prefix-match optimization broke Chinese name
        // lookups ('三' no longer matched '张三'). Pure ASCII continues
        // to use the prefix path; anything with non-ASCII falls back to
        // substring.
        service.register(700, "张三", TEST_PASSWORD, 20, "beijing", TEST_ADMIN);
        service.register(701, "李四", TEST_PASSWORD, 22, "shanghai", TEST_ADMIN);

        assertEquals(1, service.search(null, "三", 1, 10, "sno", false).totalRows());
        assertEquals(1, service.search(null, "张", 1, 10, "sno", false).totalRows());
        assertEquals(1, service.search(null, "李", 1, 10, "sno", false).totalRows());
        // Mixed ASCII + Chinese -> substring (not 'Zhang%').
        assertEquals(1, service.search(null, "张三", 1, 10, "sno", false).totalRows());
        // ASCII still uses prefix.
        service.register(702, "alice", TEST_PASSWORD, 20, "", TEST_ADMIN);
        service.register(703, "alex", TEST_PASSWORD, 20, "", TEST_ADMIN);
        assertEquals(1, service.search(null, "ali", 1, 10, "sno", false).totalRows());
    }

    @Test
    void pageSizeAndSortAreApplied() {
        service.register(800, "young", TEST_PASSWORD, 20, "", TEST_ADMIN);
        service.register(801, "older", TEST_PASSWORD, 22, "", TEST_ADMIN);
        PageResult<Student> pageOfFive = service.search(null, "", 1, 5, "sno", false);
        assertEquals(10, pageOfFive.pageSize());
        assertTrue(pageOfFive.items().size() <= 5);

        // Sort by age ascending: 20-year-olds come before 22-year-olds.
        PageResult<Student> byAge = service.search(null, "", 1, 50, "age", false);
        Integer firstAge = byAge.items().get(0).getAge();
        Integer lastAge = byAge.items().get(byAge.items().size() - 1).getAge();
        assertTrue(firstAge <= lastAge);

        // Descending reverses the order.
        PageResult<Student> byAgeDesc = service.search(null, "", 1, 50, "age", true);
        assertEquals(lastAge, byAgeDesc.items().get(0).getAge());

        // Invalid sort / page size fall back to defaults silently.
        PageResult<Student> invalid = service.search(null, "", 1, 999, "garbage", false);
        assertEquals(10, invalid.pageSize());
        assertEquals(1, invalid.page());
    }

    @Test
    void duplicateStudentIsRejected() {
        service.register(300, "first", TEST_PASSWORD, 20, "", TEST_ADMIN);
        BusinessException duplicate = assertThrows(
                BusinessException.class, () -> service.register(300, "second", TEST_PASSWORD, 20, "", TEST_ADMIN));
        assertEquals(409, duplicate.getStatus());
        assertEquals("first", service.find(300).getSname());
    }

    @Test
    void passwordChangeInvalidatesOtherSessions() {
        service.register(400, "charlie", TEST_PASSWORD, 20, "", TEST_ADMIN);
        Identity sessionA = service.login("400", TEST_PASSWORD);
        Identity sessionB = service.login("400", TEST_PASSWORD);

        service.changePassword(sessionA, TEST_PASSWORD, "Changed-test-2026");

        // sessionA's authVersion was bumped by changePassword, so current() rejects it.
        assertNull(service.current(sessionA));
        // sessionB still carries the old authVersion, so current() rejects it too.
        assertNull(service.current(sessionB));
        assertThrows(BusinessException.class, () -> service.login("400", TEST_PASSWORD));

        Identity fresh = service.login("400", "Changed-test-2026");
        assertNotNull(fresh);
    }

    @Test
    void adminPasswordResetKicksOutExistingSession() {
        service.register(500, "diana", TEST_PASSWORD, 20, "", TEST_ADMIN);
        Identity studentSession = service.login("500", TEST_PASSWORD);

        service.resetPassword(TEST_ADMIN, 500, "Admin-rotated-2026");

        // authVersion bumped, the cached Identity is no longer accepted.
        assertNull(service.current(studentSession));
        assertThrows(BusinessException.class, () -> service.login("500", TEST_PASSWORD));
        Identity fresh = service.login("500", "Admin-rotated-2026");
        assertNotNull(fresh);
    }

    @Test
    void constraintFailureRollsBackBothWrites() {
        // Add a CHECK that rejects username='99' so the account insert fails
        // after the student insert. The whole tx must roll back.
        try (Session s = factory.openSession()) {
            var tx = s.beginTransaction();
            s.createNativeMutationQuery("alter table account add constraint test_reject_99 check (username <> '99')")
                    .executeUpdate();
            tx.commit();
        }
        try {
            assertThrows(BusinessException.class, () -> service.register(99, "回滚", TEST_PASSWORD, 20, "", TEST_ADMIN));
            // Rollback verified: student 99 was never committed.
            assertEquals(
                    404,
                    assertThrows(BusinessException.class, () -> service.find(99))
                            .getStatus());
            assertTrue(service.healthy());
        } finally {
            try (Session s = factory.openSession()) {
                var tx = s.beginTransaction();
                s.createNativeMutationQuery("alter table account drop check test_reject_99")
                        .executeUpdate();
                tx.commit();
            }
        }
    }

    @Test
    void mutationsWriteAuditRows() {
        // Register, update, delete - each must leave a row in audit_log.
        service.register(600, "evan", TEST_PASSWORD, 20, "初始地址", TEST_ADMIN);
        service.update(600, "evan-renamed", 21, "新地址", TEST_ADMIN);
        service.delete(600, TEST_ADMIN);

        try (Session s = factory.openSession()) {
            @SuppressWarnings("unchecked")
            List<String> actions = s.createNativeQuery("select action from audit_log where target_sno=600 order by id")
                    .getResultList();
            assertEquals(List.of("CREATE", "UPDATE", "DELETE"), actions);
        }
    }

    @Test
    void oldHashIsUpgradedWithoutRevokingSessions() throws Exception {
        service.register(900, "legacy", TEST_PASSWORD, 20, "", TEST_ADMIN);
        byte[] salt = new byte[16];
        var spec = new javax.crypto.spec.PBEKeySpec("password123".toCharArray(), salt, 210_000, 256);
        String old = "pbkdf2-sha256$v1$210000$" + java.util.Base64.getEncoder().encodeToString(salt) + "$"
                + java.util.Base64.getEncoder()
                        .encodeToString(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(spec)
                                .getEncoded());
        spec.clearPassword();
        try (Session s = factory.openSession()) {
            var tx = s.beginTransaction();
            s.createMutationQuery("update Account a set a.passwordHash=:hash where a.username='900'")
                    .setParameter("hash", old)
                    .executeUpdate();
            tx.commit();
        }
        Identity identity = service.login("900", "password123");
        assertNotNull(service.current(identity));
        assertEquals(0, identity.authVersion());
        try (Session s = factory.openSession()) {
            String upgraded = s.createQuery("select a.passwordHash from Account a where a.username='900'", String.class)
                    .getSingleResult();
            assertTrue(upgraded.contains("$600000$"));
            assertTrue(com.utils.Passwords.verify("password123", upgraded));
        }
    }
}
