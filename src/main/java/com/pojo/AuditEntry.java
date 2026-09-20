package com.pojo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_username", nullable = false, length = 64)
    private String actorUsername;

    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "target_sno")
    private Integer targetSno;

    @Column(name = "target_username", length = 64)
    private String targetUsername;

    @Column(length = 255)
    private String details;

    // DB-side default fills the timestamp; Hibernate must not insert it.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public AuditEntry() {
    }

    public AuditEntry(String actorUsername, String actorRole, String action,
            Integer targetSno, String targetUsername, String details) {
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.action = action;
        this.targetSno = targetSno;
        this.targetUsername = targetUsername;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public Integer getTargetSno() {
        return targetSno;
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
