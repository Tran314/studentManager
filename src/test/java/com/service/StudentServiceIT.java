package com.service;

import com.pojo.Identity;
import com.pojo.PageResult;
import com.pojo.Student;
import com.utils.HibernateSessionFactoryUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against a real MySQL. Uses Testcontainers when no
 * {@code TEST_DB_URL} is in the environment, otherwise connects to the
 * MySQL supplied by {@code compose.test.yaml}. Each test uses a distinct
 * student-id range so the tests stay independent and may be reordered.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudentServiceIT {

    private static final String TEST_PASSWORD = "Student-test-2026";

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
                    .withDatabaseName("student_manager_test")
                    .withUsername("student_test")
                    .withPassword("test-only-password")
                    .withInitScripts("docker/init/001-schema.sql", "docker/init/002-index.sql");
            mysql.start();
            url = mysql.getJdbcUrl() + "?allowPublicKeyRetrieval=true&sslMode=DISABLED";
            user = mysql.getUsername();
            pass = mysql.getPassword();
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

    @Test
    @Order(1)
    void seedCreatesTenStudentsThenRegisterAddsOne() {
        service.seed("Admin-test-2026", TEST_PASSWORD);
        assertEquals(10, service.search(null, "", 1).totalRows());
        service.register(11, "张三_<测试>%", TEST_PASSWORD, 21, "香港😀");
        assertEquals("张三_<测试>%", service.find(11).getSname());
    }

    @Test
    @Order(2)
    void searchByNameAndPageBoundary() {
        service.register(100, "alice", TEST_PASSWORD, 20, "addr-a");
        service.register(101, "alex", TEST_PASSWORD, 21, "addr-b");
        service.register(102, "bob", TEST_PASSWORD, 22, "addr-c");

        assertEquals(2, service.search(null, "al", 1).totalRows());
        PageResult<Student> empty = service.search(null, "missing", 1);
        assertEquals(0, empty.totalRows());
        assertEquals(1, empty.page());
        // Out-of-range page snaps to last page, never to zero.
        PageResult<Student> over = service.search(null, "", 999);
        assertTrue(over.page() >= 1);
    }

    @Test
    @Order(3)
    void wildcardCharactersAreTreatedLiterally() {
        service.register(200, "foo_bar", TEST_PASSWORD, 20, "");
        service.register(201, "50%off", TEST_PASSWORD, 20, "");
        // Underscore and percent in user input match literal characters, not SQL wildcards.
        assertEquals(1, service.search(null, "_", 1).totalRows());
        assertEquals(1, service.search(null, "%", 1).totalRows());
    }

    @Test
    @Order(4)
    void duplicateStudentIsRejected() {
        service.register(300, "first", TEST_PASSWORD, 20, "");
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> service.register(300, "second", TEST_PASSWORD, 20, ""));
        assertEquals(409, duplicate.getStatus());
        assertEquals("first", service.find(300).getSname());
    }

    @Test
    @Order(5)
    void passwordChangeInvalidatesOtherSessions() {
        service.register(400, "charlie", TEST_PASSWORD, 20, "");
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
    @Order(6)
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
            assertThrows(BusinessException.class,
                    () -> service.register(99, "回滚", TEST_PASSWORD, 20, ""));
            // Rollback verified: student 99 was never committed.
            assertEquals(404, assertThrows(BusinessException.class, () -> service.find(99)).getStatus());
            assertTrue(service.healthy());
        } finally {
            try (Session s = factory.openSession()) {
                var tx = s.beginTransaction();
                s.createNativeMutationQuery("alter table account drop check test_reject_99").executeUpdate();
                tx.commit();
            }
        }
    }
}
