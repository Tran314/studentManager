package com.dao;

import com.pojo.Account;
import org.hibernate.Session;

public class AccountDao {
    public Account byUsername(Session session, String username) {
        return session.createQuery("from Account a left join fetch a.student where a.username=:username", Account.class)
            .setParameter("username", username).uniqueResult();
    }
    public Account byId(Session session, long id) {
        return session.createQuery("from Account a left join fetch a.student where a.id=:id", Account.class)
            .setParameter("id", id).uniqueResult();
    }
    public void deleteForStudent(Session session, int sno) {
        session.createMutationQuery("delete from Account a where a.student.sno=:sno").setParameter("sno", sno).executeUpdate();
    }
}

