package com.service;

import com.pojo.Identity;
import org.hibernate.Session;

/**
 * Writes audit-log rows from within an existing Hibernate session so the
 * audit entry is committed (or rolled back) atomically with the business
 * mutation it describes.
 */
public interface AuditService {

    /** Actions tracked by the audit log; kept as constants so logs are searchable. */
    String ACTION_CREATE = "CREATE";
    String ACTION_UPDATE = "UPDATE";
    String ACTION_DELETE = "DELETE";
    String ACTION_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    String ACTION_PASSWORD_RESET = "PASSWORD_RESET";

    void record(Session session, Identity actor, String action,
            Integer targetSno, String targetUsername, String details);
}
