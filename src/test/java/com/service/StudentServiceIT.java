package com.service;

import com.pojo.*;
import com.utils.HibernateSessionFactoryUtil;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
class StudentServiceIT {
    private static SessionFactory factory;
    private static StudentServiceImpl service;
    @BeforeAll static void open() {
        factory = HibernateSessionFactoryUtil.create(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
        service = new StudentServiceImpl(factory);
    }
    @AfterAll static void close() { if (factory != null) factory.close(); }
    @Test void lifecyclePaginationIdentityAndRestart() {
        service.seed("Admin-test-2026", "Student-test-2026");
        assertEquals(10, service.search(null, "", 1).totalRows());
        service.register(11, "张三_<测试>%", "Student-test-2026", 21, "香港😀");
        PageResult<Student> last = service.search(null, "", Integer.MAX_VALUE);
        assertEquals(2, last.page()); assertEquals(1, last.items().size());
        assertEquals(1, service.search(null, "_", 1).totalRows());
        assertEquals(1, service.search(null, "%", 1).totalRows());
        assertEquals(0, service.search(null, "missing", 1).totalRows());
        assertEquals(1, service.search(null, "missing", 99).page());

        BusinessException duplicate = assertThrows(BusinessException.class, () -> service.register(11,"重复","Student-test-2026",20,""));
        assertEquals(409, duplicate.getStatus());
        assertEquals("张三_<测试>%", service.find(11).getSname());

        Identity session1 = service.login("11", "Student-test-2026");
        Identity session2 = service.login("11", "Student-test-2026");
        service.changePassword(session1, "Student-test-2026", "Changed-test-2026");
        assertNull(service.current(session2));
        assertThrows(BusinessException.class, () -> service.login("11", "Student-test-2026"));
        Identity current = service.login("11", "Changed-test-2026");
        service.update(11, "新姓名", 22, "新地址");
        assertEquals("新姓名", service.find(11).getSname());

        // Application restart and seed repetition must not restore passwords or demo rows.
        factory.close(); open();
        service.seed("Different-admin-2026", "Different-student-2026");
        assertEquals("新姓名", service.find(11).getSname());
        assertNotNull(service.login("11", "Changed-test-2026"));
        service.delete(11);
        assertNull(service.current(current));
        assertThrows(BusinessException.class, () -> service.login("11", "Changed-test-2026"));
        assertEquals(1, service.search(null, "", 2).page());

        // Force the second write in registration to fail at the DB constraint;
        // the first (student insert) must be rolled back.
        try (var s = factory.openSession()) {
            var tx = s.beginTransaction();
            s.createNativeMutationQuery("alter table account add constraint test_reject_99 check (username <> '99')").executeUpdate();
            tx.commit();
        }
        try {
            assertThrows(BusinessException.class, () -> service.register(99, "回滚", "Student-test-2026", 20, ""));
            assertEquals(404, assertThrows(BusinessException.class, () -> service.find(99)).getStatus());
            assertTrue(service.healthy());
        } finally {
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                s.createNativeMutationQuery("alter table account drop check test_reject_99").executeUpdate();
                tx.commit();
            }
        }
    }
}

