package com.utils;

import com.pojo.Account;
import com.pojo.AuditEntry;
import com.pojo.Student;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

public final class HibernateSessionFactoryUtil {

    private HibernateSessionFactoryUtil() {}

    public static SessionFactory create(String url, String user, String password) {
        return new Configuration()
                .addAnnotatedClass(Student.class)
                .addAnnotatedClass(Account.class)
                .addAnnotatedClass(AuditEntry.class)
                .setProperty("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", user)
                .setProperty("hibernate.connection.password", password)
                .setProperty(
                        "hibernate.connection.provider_class",
                        "org.hibernate.hikaricp.internal.HikariCPConnectionProvider")
                // Hikari returns connections with autoCommit already off; Hibernate then
                // skips its own setAutoCommit calls, saving one round-trip per tx.
                .setProperty("hibernate.connection.provider_disables_autocommit", "true")
                .setProperty("hibernate.hikari.autoCommit", "false")
                .setProperty("hibernate.hikari.maximumPoolSize", "10")
                .setProperty("hibernate.hikari.minimumIdle", "1")
                .setProperty("hibernate.hikari.connectionTimeout", "10000")
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory();
    }

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
