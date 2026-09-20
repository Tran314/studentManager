package com.utils;

import com.pojo.Identity;
import com.service.*;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.slf4j.*;

@WebFilter("/*")
public class WebSecurityFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(WebSecurityFilter.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static String newToken() {
        byte[] token = new byte[32]; RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    @Override public void doFilter(ServletRequest incoming, ServletResponse outgoing, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) incoming;
        HttpServletResponse response = (HttpServletResponse) outgoing;
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        String path = request.getServletPath();
        if (path.startsWith("/assets/")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        try {
            if ("/health".equals(path)) { chain.doFilter(request, response); return; }
            HttpSession session = request.getSession();
            if (session.getAttribute("csrf") == null) session.setAttribute("csrf", newToken());
            StudentService service = (StudentService) request.getServletContext().getAttribute("studentService");
            Identity previous = (Identity) session.getAttribute("identity");
            Identity identity = service.current(previous);
            if (previous != null && identity == null) {
                session.invalidate();
                session = request.getSession();
                session.setAttribute("csrf", newToken());
            }
            request.setAttribute("identity", identity);
            boolean publicPath = "/login".equals(path) || "/register".equals(path) || path.isEmpty() || "/".equals(path);
            if (!publicPath && identity == null) {
                response.sendRedirect(request.getContextPath() + "/login"); return;
            }
            if (path.startsWith("/students") && !identity.isAdmin())
                throw new BusinessException(403, "仅管理员可以访问学生管理功能。");
            if ("/profile".equals(path) && identity.isAdmin())
                throw new BusinessException(403, "管理员请通过学生管理查看档案。");
            if ("POST".equals(request.getMethod())) {
                String expected = (String) session.getAttribute("csrf");
                String actual = request.getParameter("csrf");
                if (actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)))
                    throw new BusinessException(403, "表单已过期，请刷新页面后重试。");
            }
            Object flash = session.getAttribute("flash");
            if (flash != null) { request.setAttribute("flash", flash); session.removeAttribute("flash"); }
            chain.doFilter(request, response);
        } catch (BusinessException e) {
            renderError(request, response, e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LOG.error("Request failed ({})", e.getClass().getSimpleName());
            renderError(request, response, 500, "暂时无法完成操作，请稍后重试。");
        }
    }
    private void renderError(HttpServletRequest req, HttpServletResponse res, int status, String message)
            throws IOException, ServletException {
        if (res.isCommitted()) return;
        res.setStatus(status);
        req.setAttribute("title", "操作提示"); req.setAttribute("error", message);
        req.getRequestDispatcher("/WEB-INF/views/error.jsp").forward(req, res);
    }
}

