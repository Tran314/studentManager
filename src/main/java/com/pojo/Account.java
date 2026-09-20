package com.pojo;

import jakarta.persistence.*;

@Entity
@Table(name = "account")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 16)
    private String role;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_sno", unique = true)
    private Student student;

    @Column(name = "auth_version", nullable = false)
    private int authVersion;

    public Account() {}

    public Account(String username, String passwordHash, String role, Student student) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.student = student;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Student getStudent() {
        return student;
    }

    public int getAuthVersion() {
        return authVersion;
    }

    public void changePassword(String hash) {
        passwordHash = hash;
        authVersion++;
    }
    /** Hash rotation without authVersion bump; used by Passwords.needsRehash upgrade. */
    public void upgradeHash(String hash) {
        this.passwordHash = hash;
    }
}
