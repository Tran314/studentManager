package com.dao;

import com.pojo.Account;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

public class AccountDaoHibernateImpl implements AccountDao {

    @Override
    public Account byUsername(Session session, String username) {
        return session.createQuery("from Account a left join fetch a.student where a.username=:username", Account.class)
                .setParameter("username", username)
                .uniqueResult();
    }

    @Override
    public Account byId(Session session, long id) {
        return session.createQuery("from Account a left join fetch a.student where a.id=:id", Account.class)
                .setParameter("id", id)
                .uniqueResult();
    }

    @Override
    public Account byUsernameForUpdate(Session session, String username) {
        return session.createQuery("from Account a where a.username=:username", Account.class)
                .setParameter("username", username)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .uniqueResult();
    }

    @Override
    public Account byIdForUpdate(Session session, long id) {
        return session.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    @Override
    public void deleteForStudent(Session session, int sno) {
        session.createMutationQuery("delete from Account a where a.student.sno=:sno")
                .setParameter("sno", sno)
                .executeUpdate();
    }
}
