package com.utils;

import com.service.StudentServiceImpl;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebListener;
import org.hibernate.SessionFactory;

@WebListener
public class AppLifecycle implements ServletContextListener {
    private SessionFactory factory;
    @Override public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        SessionCookieConfig cookie = context.getSessionCookieConfig();
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setSecure(Boolean.parseBoolean(HibernateSessionFactoryUtil.env("COOKIE_SECURE", "false")));
        context.setSessionTimeout(30);
        try {
            factory = HibernateSessionFactoryUtil.create(
                HibernateSessionFactoryUtil.env("DB_URL", "jdbc:mysql://localhost:3306/student_manager?connectionTimeZone=UTC"),
                HibernateSessionFactoryUtil.env("DB_USER", "student_app"),
                HibernateSessionFactoryUtil.requiredEnv("DB_PASSWORD"));
            StudentServiceImpl service = new StudentServiceImpl(factory);
            if (Boolean.parseBoolean(HibernateSessionFactoryUtil.env("SEED_DEMO", "false")))
                service.seed(HibernateSessionFactoryUtil.requiredEnv("DEMO_ADMIN_PASSWORD"),
                    HibernateSessionFactoryUtil.requiredEnv("DEMO_STUDENT_PASSWORD"));
            context.setAttribute("studentService", service);
            // P2-4: expose input limits to JSP via applicationScope so server-side
            // validation and HTML5 maxlength share one source of truth.
            context.setAttribute("limits", Limits.get());
        } catch (RuntimeException e) {
            if (factory != null) factory.close();
            throw e;
        }
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        if (factory != null && !factory.isClosed()) factory.close();
    }
}

