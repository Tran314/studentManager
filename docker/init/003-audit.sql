-- 003-audit.sql
-- Audit log for student mutations. Records actor, action, target, and a
-- short free-form details field. Indexes target the queries that the
-- audit-listing page (future S6) and forensic searches need most.
CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_username VARCHAR(64) NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_sno INT,
    target_username VARCHAR(64),
    details VARCHAR(255),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_actor (actor_username),
    INDEX idx_audit_target (target_sno)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
