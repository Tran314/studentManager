package com.service;

import com.pojo.AuditEntry;
import com.pojo.Identity;
import org.hibernate.Session;

public class AuditServiceImpl implements AuditService {

    @Override
    public void record(
            Session session, Identity actor, String action, Integer targetSno, String targetUsername, String details) {
        if (actor == null) {
            throw new IllegalArgumentException("audit record requires an authenticated actor");
        }
        session.persist(new AuditEntry(actor.username(), actor.role(), action, targetSno, targetUsername, details));
    }
}
