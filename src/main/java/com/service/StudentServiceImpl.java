package com.service;

import com.dao.AccountDao;
import com.dao.AccountDaoHibernateImpl;
import com.dao.StudentDao;
import com.dao.StudentDaoHibernateImpl;
import com.pojo.Account;
import com.pojo.Identity;
import com.pojo.PageResult;
import com.pojo.Student;
import com.utils.Limits;
import com.utils.Messages;
import com.utils.Passwords;
import com.utils.Validation;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StudentServiceImpl implements StudentService {

    private static final Logger LOG = LoggerFactory.getLogger(StudentServiceImpl.class);

    private final SessionFactory factory;
    private final StudentDao students;
    private final AccountDao accounts;
    private final AuditService audit;
    private final String dummyHash = Passwords.hash("Dummy-login-password-9483");

    /** Production constructor; wires default Hibernate-backed DAOs and audit. */
    public StudentServiceImpl(SessionFactory factory) {
        this(factory, new StudentDaoHibernateImpl(), new AccountDaoHibernateImpl(), new AuditServiceImpl());
    }

    /** Test constructor; accepts DAOs and audit so unit tests can substitute mocks. */
    public StudentServiceImpl(SessionFactory factory, StudentDao students, AccountDao accounts, AuditService audit) {
        this.factory = factory;
        this.students = students;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Read-only transaction; uses setDefaultReadOnly so Hibernate can skip dirty-check overhead. */
    private <T> T txRead(Function<Session, T> action) {
        try (Session session = factory.openSession()) {
            session.setDefaultReadOnly(true);
            Transaction transaction = session.beginTransaction();
            try {
                T result = action.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(transaction);
                throw e;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Database read failed ({})", e.getClass().getSimpleName());
            throw e;
        }
    }

    /** Read-write transaction. One Session + one transaction per service operation. */
    private <T> T tx(Function<Session, T> action) {
        try (Session session = factory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T result = action.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(transaction);
                throw e;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (ConstraintViolationException e) {
            String name = e.getConstraintName() == null ? "" : e.getConstraintName().toLowerCase();
            if (name.contains("username") || name.contains("student_sno") || name.contains("primary")) {
                throw new BusinessException(409, Messages.ERR_DUPLICATE_LOGIN);
            }
            LOG.warn("Database constraint rejected an operation: {}", name);
            throw new BusinessException(400, Messages.ERR_CONSTRAINT);
        } catch (RuntimeException e) {
            // Do not log SQL bind values, entities or credentials.
            LOG.error("Database operation failed ({})", e.getClass().getSimpleName());
            throw e;
        }
    }

    private void rollbackQuietly(Transaction transaction) {
        try {
            if (transaction.isActive()) {
                transaction.rollback();
            }
        } catch (RuntimeException rollback) {
            LOG.error("Transaction rollback failed ({})", rollback.getClass().getSimpleName());
        }
    }

    public PageResult<Student> search(Integer sno, String name, int page) {
        String clean = Validation.text(name, "姓名", Limits.NAME_MAX, false);
        return txRead(s -> students.search(s, sno, clean, page));
    }

    private Student required(Session s, int sno) {
        Student student = students.find(s, sno);
        if (student == null) {
            throw new BusinessException(404, Messages.ERR_STUDENT_NOT_FOUND);
        }
        return student;
    }

    public Student find(int sno) {
        return txRead(s -> required(s, sno));
    }

    public void register(int sno, String name, String password, int age, String address, Identity actor) {
        validate(sno, name, age, address);
        String hash = Passwords.hash(password);
        tx(s -> {
            if (students.find(s, sno) != null || accounts.byUsername(s, String.valueOf(sno)) != null) {
                throw new BusinessException(409, Messages.ERR_DUPLICATE_SNO);
            }
            Student student = new Student(sno, name.strip(), age, address == null ? "" : address.strip());
            students.add(s, student);
            s.persist(new Account(String.valueOf(sno), hash, "STUDENT", student));
            audit.record(s, actor, AuditService.ACTION_CREATE, sno, String.valueOf(sno), null);
            return null;
        });
    }

    public void update(int sno, String name, int age, String address, Identity actor) {
        validate(sno, name, age, address);
        tx(s -> {
            Student before = required(s, sno);
            String oldName = before.getSname();
            before.update(name.strip(), age, address == null ? "" : address.strip());
            audit.record(s, actor, AuditService.ACTION_UPDATE, sno, null,
                    "from='" + oldName + "' to='" + before.getSname() + "'");
            return null;
        });
    }

    private void validate(int sno, String name, int age, String address) {
        if (sno <= 0) {
            throw new BusinessException(400, Messages.ERR_INVALID_SNO);
        }
        Validation.text(name, "姓名", Limits.NAME_MAX, true);
        Validation.text(address, "地址", Limits.ADDRESS_MAX, false);
        if (age < Limits.AGE_MIN || age > Limits.AGE_MAX) {
            throw new BusinessException(400, Messages.ERR_AGE_RANGE);
        }
    }

    public void delete(int sno, Identity actor) {
        tx(s -> {
            Student student = required(s, sno);
            accounts.deleteForStudent(s, sno);
            students.delete(s, student);
            audit.record(s, actor, AuditService.ACTION_DELETE, sno, String.valueOf(sno), null);
            return null;
        });
    }

    public Identity login(String username, String password) {
        String clean = Validation.text(username, "登录名", Limits.USERNAME_MAX, true);
        return txRead(s -> {
            Account account = accounts.byUsername(s, clean);
            boolean valid = Passwords.verify(password, account == null ? dummyHash : account.getPasswordHash());
            if (!valid || account == null) {
                throw new BusinessException(401, Messages.ERR_INVALID_CREDENTIAL);
            }
            return Identity.of(account);
        });
    }

    public Identity current(Identity previous) {
        if (previous == null) {
            return null;
        }
        return txRead(s -> {
            Account account = accounts.byId(s, previous.id());
            return account == null || account.getAuthVersion() != previous.authVersion() ? null : Identity.of(account);
        });
    }

    public void changePassword(Identity identity, String oldPassword, String newPassword) {
        Validation.password(newPassword);
        tx(s -> {
            Account account = accounts.byId(s, identity.id());
            if (account == null || account.getAuthVersion() != identity.authVersion()) {
                throw new BusinessException(401, Messages.ERR_SESSION_EXPIRED);
            }
            if (!Passwords.verify(oldPassword, account.getPasswordHash())) {
                throw new BusinessException(400, Messages.ERR_OLD_PASSWORD);
            }
            account.changePassword(Passwords.hash(newPassword));
            audit.record(s, identity, AuditService.ACTION_PASSWORD_CHANGE, null, identity.username(), null);
            return null;
        });
    }

    /**
     * Admin-only: rotate a student's password without knowing the old one.
     * Bumps authVersion so all of the student's other open sessions are
     * kicked out on their next request.
     */
    public void resetPassword(Identity admin, int sno, String newPassword) {
        if (admin == null || !admin.isAdmin()) {
            throw new BusinessException(403, Messages.ERR_ADMIN_ONLY_RESET);
        }
        Validation.password(newPassword);
        tx(s -> {
            Student student = required(s, sno);
            Account account = accounts.byUsername(s, String.valueOf(sno));
            if (account == null) {
                throw new BusinessException(404, Messages.ERR_STUDENT_ACCOUNT_NOT_FOUND);
            }
            account.changePassword(Passwords.hash(newPassword));
            audit.record(s, admin, AuditService.ACTION_PASSWORD_RESET, sno, account.getUsername(), null);
            return null;
        });
    }

    public boolean healthy() {
        return txRead(s -> s.createNativeQuery("select 1", Integer.class).getSingleResult() == 1);
    }

    public void seed(String adminPassword, String studentPassword) {
        tx(s -> {
            Number done = (Number) s.createNativeQuery("select count(*) from app_seed where id=1", Integer.class)
                    .getSingleResult();
            if (done.intValue() != 0) {
                return null;
            }
            String adminHash = Passwords.hash(adminPassword);
            s.persist(new Account("admin", adminHash, "ADMIN", null));
            String[] names = {"lucy", "jack", "rose", "tom", "coco", "lucy2", "jack2", "rose2", "tom2", "coco2"};
            String[] addresses = {"xianghai", "hongkong", "tibet", "fuzhou", "beijing"};
            int[] ages = {20, 23, 25, 27, 30};
            for (int i = 0; i < 10; i++) {
                Student student = new Student(i + 1, names[i], ages[i % 5], addresses[i % 5]);
                students.add(s, student);
                s.persist(new Account(String.valueOf(i + 1), Passwords.hash(studentPassword), "STUDENT", student));
            }
            s.createNativeMutationQuery("insert into app_seed(id) values(1)").executeUpdate();
            return null;
        });
    }
}
