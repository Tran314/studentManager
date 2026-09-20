package com.servlets;

import com.pojo.Identity;
import com.utils.Messages;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Single source of truth for servlet path -> (view, title) mapping.
 *
 * <p>Replaces the four-times-duplicated path strings that previously
 * appeared in the doGet / doPost / error-recovery switches. Routes whose
 * GET path returns no view (logout, health, delete) keep {@code view = null}.
 */
enum Route {
    HOME("", null, ""),
    LOGIN("/login", "login", Messages.TITLE_LOGIN),
    LOGOUT("/logout", null, ""),
    HEALTH("/health", null, ""),
    STUDENTS("/students", "students", "学生管理"),
    STUDENTS_EXPORT("/students/export", null, ""),
    STUDENT_DETAIL("/students/detail", "detail", "学生详情"),
    STUDENT_CREATE("/students/create", "student-form", "新增学生"),
    STUDENT_EDIT("/students/edit", "student-form", "编辑学生"),
    STUDENT_RESET("/students/reset", "student-reset", "重置学生密码"),
    STUDENT_DELETE("/students/delete", null, ""),
    PROFILE("/profile", "student-form", "个人中心"),
    PASSWORD("/password", "password", "修改密码");

    final String path;
    final String view;
    final String title;

    Route(String path, String view, String title) {
        this.path = path;
        this.view = view;
        this.title = title;
    }

    static Route of(String servletPath) {
        for (Route r : values()) {
            if (r.path.equals(servletPath)) {
                return r;
            }
        }
        return null;
    }

    boolean isStudentForm() {
        return this == STUDENT_CREATE || this == STUDENT_EDIT || this == PROFILE;
    }

    boolean isCreating() {
        return this == STUDENT_CREATE;
    }

    boolean isStudentMutation() {
        return this == STUDENT_CREATE || this == STUDENT_EDIT || this == STUDENT_RESET || this == PROFILE;
    }

    /** Landing redirect target for unauthenticated vs. admin vs. student users. */
    String homeRedirect(HttpServletRequest req) {
        Identity identity = (Identity) req.getAttribute("identity");
        if (identity == null) {
            return "/login";
        }
        return identity.isAdmin() ? "/students" : "/profile";
    }
}
