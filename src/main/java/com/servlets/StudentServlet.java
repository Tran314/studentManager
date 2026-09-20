package com.servlets;

import com.pojo.Identity;
import com.service.BusinessException;
import com.service.StudentService;
import com.utils.Validation;
import com.utils.WebSecurityFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet(urlPatterns = {
        "", "/login", "/logout", "/students", "/students/detail",
        "/students/create", "/students/edit", "/students/delete", "/profile", "/password", "/health"
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
        return Validation.positiveInt(req.getParameter("sno"), "学号", Integer.MAX_VALUE);
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

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        switch (path(req)) {
            case "", "/" -> redirect(req, res,
                    identity(req) == null ? "/login" : identity(req).isAdmin() ? "/students" : "/profile",
                    null);
            case "/login" -> view(req, res, "login", "欢迎回来");
            case "/students/create" -> {
                req.setAttribute("creating", true);
                view(req, res, "student-form", "新增学生");
            }
            case "/students" -> {
                String snoText = req.getParameter("sno");
                Integer number = snoText == null || snoText.isBlank() ? null : snoParam(req);
                String name = Validation.text(req.getParameter("name"), "姓名", 20, false);
                req.setAttribute("result", service().search(number, name, Validation.page(req.getParameter("page"))));
                view(req, res, "students", "学生管理");
            }
            case "/students/detail" -> {
                req.setAttribute("student", service().find(snoParam(req)));
                view(req, res, "detail", "学生详情");
            }
            case "/students/edit", "/profile" -> {
                int number = "/profile".equals(path(req)) ? identity(req).studentSno() : snoParam(req);
                req.setAttribute("student", service().find(number));
                view(req, res, "student-form", "/profile".equals(path(req)) ? "个人中心" : "编辑学生");
            }
            case "/password" -> view(req, res, "password", "修改密码");
            case "/health" -> {
                res.setContentType("text/plain;charset=UTF-8");
                try {
                    res.setStatus(service().healthy() ? 200 : 503);
                    res.getWriter().print("OK");
                } catch (RuntimeException e) {
                    res.setStatus(503);
                    res.getWriter().print("UNAVAILABLE");
                }
            }
            default -> {
                res.setStatus(405);
                res.setHeader("Allow", "POST");
                view(req, res, "error", "请通过表单提交");
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        try {
            switch (path(req)) {
                case "/login" -> {
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
                    redirect(req, res, identity.isAdmin() ? "/students" : "/profile", "登录成功，欢迎回来。");
                }
                case "/logout" -> {
                    req.getSession().invalidate();
                    redirect(req, res, "/login", "已安全退出。");
                }
                case "/students/create", "/students/edit", "/profile" -> {
                    String route = path(req);
                    boolean creating = "/students/create".equals(route);
                    int number = "/profile".equals(route) ? identity(req).studentSno() : snoParam(req);
                    String name = Validation.text(req.getParameter("sname"), "姓名", 20, true);
                    int age = Validation.positiveInt(req.getParameter("age"), "年龄", 150);
                    String address = Validation.text(req.getParameter("address"), "地址", 50, false);
                    if (creating) {
                        service().register(number, name, req.getParameter("password"), age, address);
                    } else {
                        service().update(number, name, age, address);
                    }
                    redirect(req, res,
                            "/profile".equals(route) ? "/profile" : "/students",
                            "学生资料已保存。");
                }
                case "/students/delete" -> {
                    service().delete(snoParam(req));
                    redirect(req, res, "/students", "学生及关联账号已删除。");
                }
                case "/password" -> {
                    try {
                        if (req.getParameter("newPassword") == null
                                || !req.getParameter("newPassword").equals(req.getParameter("confirmPassword"))) {
                            throw new BusinessException(400, "两次输入的新密码不一致。");
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
                    redirect(req, res, "/login", "密码已修改，请重新登录。");
                }
                default -> {
                    res.setStatus(405);
                    res.setHeader("Allow", "GET");
                    view(req, res, "error", "不支持此操作");
                }
            }
        } catch (BusinessException e) {
            if (e.getStatus() == 403 || e.getStatus() == 404) {
                throw e;
            }
            res.setStatus(e.getStatus());
            req.setAttribute("error", e.getMessage());
            req.setAttribute("submitted", true);
            switch (path(req)) {
                case "/login" -> view(req, res, "login", "欢迎回来");
                case "/password" -> view(req, res, "password", "修改密码");
                case "/students/create", "/students/edit", "/profile" -> {
                    req.setAttribute("creating", "/students/create".equals(path(req)));
                    if ("/profile".equals(path(req))) {
                        req.setAttribute("student", service().find(identity(req).studentSno()));
                    }
                    view(req, res, "student-form", "请检查学生资料");
                }
                default -> throw e;
            }
        }
    }
}
