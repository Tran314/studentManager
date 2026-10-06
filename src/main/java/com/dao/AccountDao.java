package com.dao;

import com.pojo.Account;
import org.hibernate.Session;

/**
 * Account persistence boundary. Unlocks Mockito-based unit tests of the
 * service layer without a live MySQL/Hibernate session factory.
 */
public interface AccountDao {

    Account byUsername(Session session, String username);

    Account byId(Session session, long id);

    Account byUsernameForUpdate(Session session, String username);

    Account byIdForUpdate(Session session, long id);

    void deleteForStudent(Session session, int sno);
}
