package com.servlets;

import com.pojo.Identity;
import com.service.BusinessException;
import com.service.StudentService;
import com.utils.Limits;
import com.utils.Messages;
import com.utils.Validation;
import com.utils.WebSecurityFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet(urlPatterns = {
        "", "/login", "/logout", "/students", "/students/detail", "/students/export",
        "/students/create", "/students/edit", "/students/reset", "/students/delete",
        "/profile", "/password", "/health"
})
public class StudentServlet extends HttpServlet {

    private StudentService service() {
        return (StudentService) getServletContext().getAttribute("studentService");
    }

    private Identity identity(HttpServletRequest req) {
        return (Identity) req.getAttribute("identity");
    }

    private String path(HttpServletRequest req) {
        return req.getServletPath();
    }

    private int snoParam(HttpServletRequest req) {
        return Validation.positiveInt(req.getParameter("sno"), "学号", Limits.SNO_MAX);
    }

    private void view(HttpServletRequest req, HttpServletResponse res, String page, String title)
            throws ServletException, IOException {
        req.setAttribute("title", title);
        req.getRequestDispatcher("/WEB-INF/views/" + page + ".jsp").forward(req, res);
    }

    private void redirect(HttpServletRequest req, HttpServletResponse res, String route, String flash) throws IOException {
        if (flash != null) {
            req.getSession().setAttribute("flash", flash);
        }
        res.sendRedirect(req.getContextPath() + route);
    }

    private void renderRoute(HttpServletRequest req, HttpServletResponse res, Route route) throws ServletException, IOException {
        view(req, res, route.view, route.title);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        String path = path(req);

        if (path.isEmpty() || "/".equals(path)) {
            Route home = Route.HOME;
            redirect(req, res, home.homeRedirect(req), null);
            return;
        }

        if ("/health".equals(path)) {
            res.setContentType("text/plain;charset=UTF-8");
            try {
                res.setStatus(service().healthy() ? 200 : 503);
                res.getWriter().print("OK");
            } catch (RuntimeException e) {
                res.setStatus(503);
                res.getWriter().print("UNAVAILABLE");
            }
            return;
        }

        Route route = Route.of(path);
        if (route == null) {
            res.setStatus(405);
            res.setHeader("Allow", "POST");
            view(req, res, "error", Messages.ERR_METHOD_NOT_ALLOWED_GET);
            return;
        }
        if (route == Route.STUDENTS_EXPORT) {
            // Route.STUDENTS_EXPORT has view=null on purpose - it writes the
            // response body directly. Skip the view==null 405 short-circuit.
            writeCsv(req, res);
            return;
        }
        if (route.view == null) {
            res.setStatus(405);
            res.setHeader("Allow", "POST");
            view(req, res, "error", Messages.ERR_METHOD_NOT_ALLOWED_GET);
            return;
        }

        switch (route) {
            case STUDENTS -> {
                req.setAttribute("result", searchStudents(req));
                renderRoute(req, res, route);
            }
            case STUDENT_DETAIL -> {
                req.setAttribute("student", service().find(snoParam(req)));
                renderRoute(req, res, route);
            }
            case STUDENT_CREATE -> {
                req.setAttribute("creating", true);
                renderRoute(req, res, route);
            }
            case STUDENT_RESET -> {
                req.setAttribute("student", service().find(snoParam(req)));
                renderRoute(req, res, route);
            }
            case STUDENT_EDIT, PROFILE -> {
                int number = route == Route.PROFILE ? identity(req).studentSno() : snoParam(req);
                req.setAttribute("student", service().find(number));
                renderRoute(req, res, route);
            }
            default -> renderRoute(req, res, route);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        try {
            switch (path(req)) {
                case "/login" -> handleLogin(req, res);
                case "/logout" -> {
                    req.getSession().invalidate();
                    redirect(req, res, Route.LOGIN.path, Messages.TITLE_LOGOUT);
                }
                case "/students/create", "/students/edit", "/profile" -> handleStudentMutation(req, res, path(req));
                case "/students/delete" -> {
                    service().delete(snoParam(req), identity(req));
                    redirect(req, res, Route.STUDENTS.path, Messages.TITLE_STUDENT_DELETED);
                }
                case "/students/reset" -> handleResetPassword(req, res);
                case "/password" -> handlePassword(req, res);
                default -> {
                    res.setStatus(405);
                    res.setHeader("Allow", "GET");
                    view(req, res, "error", Messages.ERR_METHOD_NOT_ALLOWED_POST);
                }
            }
        } catch (BusinessException e) {
            if (e.getStatus() == 403 || e.getStatus() == 404) {
                throw e;
            }
            res.setStatus(e.getStatus());
            req.setAttribute("error", e.getMessage());
            req.setAttribute("submitted", true);
            Route route = Route.of(path(req));
            if (route == null) {
                throw e;
            }
            switch (route) {
                case LOGIN -> view(req, res, Route.LOGIN.view, Route.LOGIN.title);
                case PASSWORD -> view(req, res, Route.PASSWORD.view, Route.PASSWORD.title);
                case STUDENT_CREATE, STUDENT_EDIT, PROFILE -> {
                    req.setAttribute("creating", route.isCreating());
                    if (route == Route.PROFILE) {
                        req.setAttribute("student", service().find(identity(req).studentSno()));
                    }
                    view(req, res, "student-form", Messages.TITLE_FORM_CHECK);
                }
                case STUDENT_RESET -> view(req, res, Route.STUDENT_RESET.view, Route.STUDENT_RESET.title);
                default -> throw e;
            }
        }
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Identity identity;
        try {
            identity = service().login(req.getParameter("username"), req.getParameter("password"));
        } catch (BusinessException e) {
            req.setAttribute("rateLimitResult", "failure");
            throw e;
        }
        req.changeSessionId();
        req.getSession().setAttribute("identity", identity);
        req.getSession().setAttribute("csrf", WebSecurityFilter.newToken());
        req.setAttribute("rateLimitResult", "success");
        redirect(req, res, identity.isAdmin() ? Route.STUDENTS.path : Route.PROFILE.path, Messages.TITLE_LOGIN_SUCCESS);
    }

    private void handleStudentMutation(HttpServletRequest req, HttpServletResponse res, String route) throws IOException {
        boolean creating = "/students/create".equals(route);
        int number = "/profile".equals(route) ? identity(req).studentSno() : snoParam(req);
        String name = Validation.text(req.getParameter("sname"), "姓名", Limits.NAME_MAX, true);
        int age = Validation.positiveInt(req.getParameter("age"), "年龄", Limits.AGE_MAX);
        String address = Validation.text(req.getParameter("address"), "地址", Limits.ADDRESS_MAX, false);
        Identity actor = identity(req);
        if (creating) {
            service().register(number, name, req.getParameter("password"), age, address, actor);
        } else {
            service().update(number, name, age, address, actor);
        }
        String target = "/profile".equals(route) ? Route.PROFILE.path : Route.STUDENTS.path;
        redirect(req, res, target, Messages.TITLE_STUDENT_SAVED);
    }

    private void handlePassword(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            if (req.getParameter("newPassword") == null
                    || !req.getParameter("newPassword").equals(req.getParameter("confirmPassword"))) {
                throw new BusinessException(400, Messages.ERR_PASSWORD_MISMATCH);
            }
            service().changePassword(identity(req),
                    req.getParameter("oldPassword"),
                    req.getParameter("newPassword"));
        } catch (BusinessException e) {
            req.setAttribute("rateLimitResult", "failure");
            throw e;
        }
        req.setAttribute("rateLimitResult", "success");
        req.getSession().invalidate();
        redirect(req, res, Route.LOGIN.path, Messages.TITLE_PASSWORD_CHANGED);
    }

    private void handleResetPassword(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            String newPassword = req.getParameter("newPassword");
            String confirm = req.getParameter("confirmPassword");
            if (newPassword == null || !newPassword.equals(confirm)) {
                throw new BusinessException(400, Messages.ERR_PASSWORD_MISMATCH);
            }
            service().resetPassword(identity(req), snoParam(req), newPassword);
        } catch (BusinessException e) {
            req.setAttribute("rateLimitResult", "failure");
            throw e;
        }
        req.setAttribute("rateLimitResult", "success");
        redirect(req, res, Route.STUDENTS.path, Messages.TITLE_PASSWORD_RESET);
    }

    private com.pojo.PageResult<com.pojo.Student> searchStudents(HttpServletRequest req) {
        String snoText = req.getParameter("sno");
        Integer number = snoText == null || snoText.isBlank() ? null : snoParam(req);
        String name = Validation.text(req.getParameter("name"), "姓名", Limits.NAME_MAX, false);
        int page = Validation.page(req.getParameter("page"));
        int size = parsePageSize(req.getParameter("size"));
        String sort = req.getParameter("sort");
        boolean descending = "desc".equalsIgnoreCase(req.getParameter("dir"));
        req.setAttribute("size", size);
        req.setAttribute("sort", sort == null ? "sno" : sort);
        req.setAttribute("dir", descending ? "desc" : "asc");
        return service().search(number, name, page, size, sort, descending);
    }

    private static int parsePageSize(String raw) {
        if (raw == null) {
            return Limits.PAGE_SIZE;
        }
        return switch (raw) {
            case "20" -> 20;
            case "50" -> 50;
            default -> Limits.PAGE_SIZE;
        };
    }

    private void writeCsv(HttpServletRequest req, HttpServletResponse res) throws IOException {
        com.pojo.PageResult<com.pojo.Student> result = searchStudents(req);
        res.setContentType("text/csv; charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"students.csv\"");
        // BOM lets Excel for Windows detect UTF-8 instead of mis-decoding to GBK.
        res.getWriter().write("\uFEFF");
        res.getWriter().write("学号,姓名,年龄,地址\r\n");
        for (com.pojo.Student s : result.items()) {
            res.getWriter().write(s.getSno() + "," + csv(s.getSname()) + "," + s.getAge() + "," + csv(s.getAddress()) + "\r\n");
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        // Mitigate CSV formula injection: leading '=', '+', '-', '@', TAB or CR
        // would let Excel execute the cell as a formula on open.
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                value = "'" + value;
            }
        }
        // RFC 4180 quoting: wrap in quotes when the field contains a separator,
        // a quote, or a newline; double any embedded quote.
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
