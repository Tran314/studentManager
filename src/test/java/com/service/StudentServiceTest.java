package com.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dao.*;
import com.pojo.*;
import com.utils.Passwords;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.*;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StudentServiceTest {
    private final SessionFactory factory = mock(SessionFactory.class);
    private final Session session = mock(Session.class);
    private final Transaction tx = mock(Transaction.class);
    private final StudentDao students = mock(StudentDao.class);
    private final AccountDao accounts = mock(AccountDao.class);
    private final AuditService audit = mock(AuditService.class);
    private final Identity admin = new Identity(1, "admin", "ADMIN", null, 0);
    private final Student student = new Student(42, "before", 20, "");
    private StudentServiceImpl service;
    private static final String PASSWORD = "Test-secret-2026";
    private static final String HASH = Passwords.hash(PASSWORD);

    @BeforeEach
    void setup() {
        when(factory.openSession()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(tx);
        when(tx.isActive()).thenReturn(true);
        service = new StudentServiceImpl(factory, students, accounts, audit);
    }

    private Account account() {
        Account a = spy(new Account("42", HASH, "STUDENT", student));
        when(a.getId()).thenReturn(2L);
        return a;
    }

    private void status(int expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(BusinessException.class, call).getStatus());
    }

    @Test
    void readsAreReadOnlyAndPaginationIsWhitelisted() {
        PageResult<Student> page = new PageResult<>(List.of(student), 1, 10, 1, 1);
        when(students.search(session, null, "alice", 1, 10, "sno", false)).thenReturn(page);
        assertSame(page, service.search(null, " alice ", 1, 5, "malicious", false));
        service.search(null, "", 2, 20, "name", true);
        service.search(null, "", 2, 50, "age", true);
        service.search(null, "", 2, 10, null, false);
        when(students.find(session, 42)).thenReturn(student);
        assertSame(student, service.find(42));
        status(404, () -> service.find(43));
        verify(session, atLeastOnce()).setDefaultReadOnly(true);
        verify(tx, atLeastOnce()).commit();
        verify(tx).rollback();
    }

    @Test
    void mutationsPersistAndAuditTogether() {
        service.register(42, " new ", PASSWORD, 20, null, admin);
        verify(students)
                .add(
                        eq(session),
                        argThat(s ->
                                s.getSname().equals("new") && s.getAddress().isEmpty()));
        verify(session).persist(any(Account.class));
        when(students.find(session, 42)).thenReturn(student);
        service.update(42, "changed", 21, null, admin);
        assertEquals("changed", student.getSname());
        service.delete(42, admin);
        verify(accounts).deleteForStudent(session, 42);
        verify(students).delete(session, student);
        verify(audit).record(session, admin, AuditService.ACTION_DELETE, 42, "42", null);
    }

    @Test
    void duplicateAndInvalidInputAreRejected() {
        when(students.find(session, 42)).thenReturn(student);
        status(409, () -> service.register(42, "name", PASSWORD, 20, "", admin));
        Account existing = account();
        when(accounts.byUsername(session, "43")).thenReturn(existing);
        status(409, () -> service.register(43, "name", PASSWORD, 20, "", admin));
        status(400, () -> service.register(0, "name", PASSWORD, 20, "", admin));
        status(400, () -> service.update(42, "name", 0, "", admin));
        status(400, () -> service.update(42, "name", 151, "", admin));
    }

    @Test
    void constraintsUseVendorCodeInsteadOfAmbiguousNames() {
        doThrow(new ConstraintViolationException("duplicate", new SQLException("", "23000", 1062), "username"))
                .when(students)
                .add(eq(session), any());
        status(409, () -> service.register(42, "name", PASSWORD, 20, "", admin));
        doThrow(new ConstraintViolationException("check", new SQLException("", "HY000", 3819), "student_sno_positive"))
                .when(students)
                .add(eq(session), any());
        status(400, () -> service.register(42, "name", PASSWORD, 20, "", admin));
        verify(tx, times(2)).rollback();
    }

    @Test
    void rollbackFailuresDoNotHideOriginalFailure() {
        when(students.find(session, 42)).thenThrow(new IllegalStateException("read failed"));
        doThrow(new IllegalStateException("rollback failed")).when(tx).rollback();
        assertEquals(
                "read failed",
                assertThrows(IllegalStateException.class, () -> service.find(42))
                        .getMessage());
        assertThrows(IllegalStateException.class, () -> service.delete(42, admin));
    }

    @Test
    void loginAndRevocationUseDatabaseVersion() {
        Account a = account();
        when(accounts.byUsername(session, "42")).thenReturn(a);
        Identity identity = service.login(" 42 ", PASSWORD);
        assertEquals(42, identity.studentSno());
        status(401, () -> service.login("42", "wrong"));
        status(401, () -> service.login("unknown", PASSWORD));
        assertNull(service.current(null));
        assertNull(service.current(identity));
        when(accounts.byId(session, 2)).thenReturn(a);
        assertEquals(identity, service.current(identity));
        a.changePassword(HASH);
        assertNull(service.current(identity));
    }

    @Test
    void passwordChangesRequireCurrentVersionAndOldPassword() {
        Account a = account();
        Identity identity = Identity.of(a);
        status(401, () -> service.changePassword(identity, PASSWORD, "Another-secret-2026"));
        when(accounts.byIdForUpdate(session, 2)).thenReturn(a);
        status(400, () -> service.changePassword(identity, "bad", "Another-secret-2026"));
        service.changePassword(identity, PASSWORD, "Another-secret-2026");
        assertEquals(1, a.getAuthVersion());
        assertTrue(Passwords.verify("Another-secret-2026", a.getPasswordHash()));
        status(401, () -> service.changePassword(identity, PASSWORD, "Another-secret-2026"));
    }

    @Test
    void resetRequiresAdminAndExistingAccount() {
        Account a = account();
        status(403, () -> service.resetPassword(null, 42, PASSWORD));
        status(403, () -> service.resetPassword(Identity.of(a), 42, PASSWORD));
        status(404, () -> service.resetPassword(admin, 42, PASSWORD));
        when(students.find(session, 42)).thenReturn(student);
        status(404, () -> service.resetPassword(admin, 42, PASSWORD));
        when(accounts.byUsernameForUpdate(session, "42")).thenReturn(a);
        service.resetPassword(admin, 42, "Reset-secret-2026");
        assertEquals(1, a.getAuthVersion());
        verify(audit).record(session, admin, AuditService.ACTION_PASSWORD_RESET, 42, "42", null);
    }

    @Test
    void invalidLoginNamesCannotReachDatabaseLookup() {
        for (String username : new String[] {
            null,
            "",
            " ",
            "ad\u200bmin",
            "ad\u00admin",
            "admiñ",
            "ＡＤＭＩＮ",
            "admin\u0000",
            "a".repeat(65),
            "a".repeat(129)
        }) {
            status(401, () -> service.login(username, PASSWORD));
        }
        verify(accounts, never()).byUsername(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthAndSeedAreTransactionalAndIdempotent() {
        NativeQuery<Integer> health = mock(NativeQuery.class);
        when(session.createNativeQuery("select 1", Integer.class)).thenReturn(health);
        when(health.getSingleResult()).thenReturn(1, 0);
        assertTrue(service.healthy());
        assertFalse(service.healthy());
        NativeQuery<Integer> count = mock(NativeQuery.class);
        when(session.createNativeQuery("select count(*) from app_seed where id=1", Integer.class))
                .thenReturn(count);
        when(count.getSingleResult()).thenReturn(0, 1);
        MutationQuery marker = mock(MutationQuery.class);
        when(session.createNativeMutationQuery("insert into app_seed(id) values(1)"))
                .thenReturn(marker);
        service.seed("Admin-secret-2026", PASSWORD);
        service.seed("Admin-secret-2026", PASSWORD);
        verify(students, times(10)).add(eq(session), any());
        verify(session, times(11)).persist(any(Account.class));
        verify(marker).executeUpdate();
    }
}
