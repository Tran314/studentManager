package com.service;

import com.dao.*;
import com.pojo.*;
import com.utils.*;
import java.util.function.Function;
import org.hibernate.*;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.*;

public class StudentServiceImpl implements StudentService {
    private static final Logger LOG = LoggerFactory.getLogger(StudentServiceImpl.class);
    private final SessionFactory factory;
    private final StudentDao students = new StudentDaoHibernateImpl();
    private final AccountDao accounts = new AccountDao();
    private final String dummyHash = Passwords.hash("Dummy-login-password-9483");
    public StudentServiceImpl(SessionFactory factory) { this.factory = factory; }

    /** One Session and one transaction per service operation, including reads. */
    private <T> T tx(Function<Session, T> action) {
        try (Session session = factory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T result = action.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException e) {
                try { if (transaction.isActive()) transaction.rollback(); }
                catch (RuntimeException rollback) { LOG.error("Transaction rollback failed ({})", rollback.getClass().getSimpleName()); }
                throw e;
            }
        } catch (BusinessException e) { throw e;
        } catch (ConstraintViolationException e) {
            String name = e.getConstraintName() == null ? "" : e.getConstraintName().toLowerCase();
            if (name.contains("username") || name.contains("student_sno") || name.contains("primary")) {
                throw new BusinessException(409, "学号或登录名已存在，请使用其他学号。");
            }
            LOG.warn("Database constraint rejected an operation: {}", name);
            throw new BusinessException(400, "提交的数据不符合约束要求。");
        } catch (RuntimeException e) {
            // Do not log SQL bind values, entities or credentials.
            LOG.error("Database operation failed ({})", e.getClass().getSimpleName());
            throw e;
        }
    }
    public PageResult<Student> search(Integer sno, String name, int page) {
        String clean = Validation.text(name, "姓名", 20, false);
        return tx(s -> students.search(s, sno, clean, page));
    }
    private Student required(Session s, int sno) {
        Student student = students.find(s, sno);
        if (student == null) throw new BusinessException(404, "学生记录不存在。");
        return student;
    }
    public Student find(int sno) { return tx(s -> required(s, sno)); }
    public void register(int sno, String name, String password, int age, String address) {
        validate(sno, name, age, address);
        String hash = Passwords.hash(password);
        tx(s -> {
            if (students.find(s, sno) != null || accounts.byUsername(s, String.valueOf(sno)) != null)
                throw new BusinessException(409, "学号已存在，请使用其他学号。");
            Student student = new Student(sno, name.strip(), age, address == null ? "" : address.strip());
            students.add(s, student);
            s.persist(new Account(String.valueOf(sno), hash, "STUDENT", student));
            return null;
        });
    }
    public void update(int sno, String name, int age, String address) {
        validate(sno, name, age, address);
        tx(s -> { required(s, sno).update(name.strip(), age, address == null ? "" : address.strip()); return null; });
    }
    private void validate(int sno, String name, int age, String address) {
        if (sno <= 0) throw new BusinessException(400, "学号必须为正整数。");
        Validation.text(name, "姓名", 20, true); Validation.text(address, "地址", 50, false);
        if (age < 1 || age > 150) throw new BusinessException(400, "年龄必须为1–150的整数。");
    }
    public void delete(int sno) {
        tx(s -> {
            Student student = required(s, sno);
            accounts.deleteForStudent(s, sno);
            students.delete(s, student);
            return null;
        });
    }
    public Identity login(String username, String password) {
        String clean = Validation.text(username, "登录名", 64, true);
        return tx(s -> {
            Account account = accounts.byUsername(s, clean);
            boolean valid = Passwords.verify(password, account == null ? dummyHash : account.getPasswordHash());
            if (!valid || account == null) throw new BusinessException(401, "登录名或密码不正确。");
            return Identity.of(account);
        });
    }
    public Identity current(Identity previous) {
        if (previous == null) return null;
        return tx(s -> {
            Account account = accounts.byId(s, previous.id());
            return account == null || account.getAuthVersion() != previous.authVersion() ? null : Identity.of(account);
        });
    }
    public void changePassword(Identity identity, String oldPassword, String newPassword) {
        Validation.password(newPassword);
        tx(s -> {
            Account account = accounts.byId(s, identity.id());
            if (account == null || account.getAuthVersion() != identity.authVersion())
                throw new BusinessException(401, "登录已过期，请重新登录。");
            if (!Passwords.verify(oldPassword, account.getPasswordHash()))
                throw new BusinessException(400, "当前密码不正确。");
            account.changePassword(Passwords.hash(newPassword));
            return null;
        });
    }
    public boolean healthy() {
        return tx(s -> s.createNativeQuery("select 1", Integer.class).getSingleResult() == 1);
    }
    public void seed(String adminPassword, String studentPassword) {
        tx(s -> {
            Number done = (Number) s.createNativeQuery("select count(*) from app_seed where id=1", Integer.class).getSingleResult();
            if (done.intValue() != 0) return null;
            String adminHash = Passwords.hash(adminPassword);
            s.persist(new Account("admin", adminHash, "ADMIN", null));
            String[] names = {"lucy","jack","rose","tom","coco","lucy2","jack2","rose2","tom2","coco2"};
            String[] addresses = {"xianghai","hongkong","tibet","fuzhou","beijing"};
            int[] ages = {20,23,25,27,30};
            for (int i=0; i<10; i++) {
                Student student = new Student(i+1, names[i], ages[i%5], addresses[i%5]);
                students.add(s, student);
                s.persist(new Account(String.valueOf(i+1), Passwords.hash(studentPassword), "STUDENT", student));
            }
            s.createNativeMutationQuery("insert into app_seed(id) values(1)").executeUpdate();
            return null;
        });
    }
}

