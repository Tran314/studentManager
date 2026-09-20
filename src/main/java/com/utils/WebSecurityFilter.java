package com.utils;

import com.pojo.Identity;
import com.service.BusinessException;
import com.service.StudentService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@WebFilter(
        urlPatterns = "/*",
        dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ERROR})
public class WebSecurityFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(WebSecurityFilter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RateLimiter limiter = new RateLimiter();

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER = "user";

    public static String newToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static String newRequestId() {
        byte[] token = new byte[8];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    @Override
    public void doFilter(ServletRequest incoming, ServletResponse outgoing, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) incoming;
        HttpServletResponse response = (HttpServletResponse) outgoing;
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        setSecurityHeaders(request, response);

        String path = request.getServletPath();
        if (path.startsWith("/assets/")) {
            // P1-4: fingerprinted assets (app.<hash>.css, app.<hash>.js) are content-stable
            // and safe to cache for a year. Browsers never revalidate within max-age=31536000.
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");

        if ("/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        // P0-1: public registration is permanently closed; students are created by admins only.
        if ("/register".equals(path)) {
            renderError(request, response, 410, Messages.ERR_PUBLIC_REGISTRATION_CLOSED);
            return;
        }

        // P0-5: ERROR dispatch from web.xml error-page. Skip session/auth/CSRF/rate-limit to avoid recursion.
        boolean isErrorDispatch = request.getAttribute("jakarta.servlet.error.request_uri") != null;

        String requestId = newRequestId();
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            if (isErrorDispatch) {
                request.setAttribute("identity", loadIdentityQuietly(request));
                chain.doFilter(request, response);
                return;
            }

            try {
                // P0-4: lazy session creation. Anonymous GETs no longer leak 30-minute sessions on every scan.
                HttpSession session = request.getSession(false);
                boolean needsSession = "GET".equals(request.getMethod()) && "/login".equals(path);
                if (session == null && needsSession) {
                    session = request.getSession(true);
                }
                if (session != null && session.getAttribute("csrf") == null) {
                    session.setAttribute("csrf", newToken());
                }

                StudentService service =
                        (StudentService) request.getServletContext().getAttribute("studentService");
                Identity previous = session == null ? null : (Identity) session.getAttribute("identity");
                // Revalidate each request: changes and deletion revoke sessions immediately.
                Identity identity = previous == null ? null : service.current(previous);
                if (previous != null && identity == null && session != null) {
                    session.invalidate();
                    session = needsSession ? request.getSession(true) : null;
                    if (session != null) {
                        session.setAttribute("csrf", newToken());
                    }
                }
                request.setAttribute("identity", identity);
                if (identity != null) {
                    MDC.put(MDC_USER, identity.username());
                }

                boolean publicPath = "/login".equals(path) || path.isEmpty() || "/".equals(path);
                if (!publicPath && identity == null) {
                    response.sendRedirect(request.getContextPath() + "/login");
                    return;
                }
                if (path.startsWith("/students") && identity != null && !identity.isAdmin()) {
                    throw new BusinessException(403, Messages.ERR_ADMIN_ONLY_STUDENTS);
                }
                if ("/profile".equals(path) && identity != null && identity.isAdmin()) {
                    throw new BusinessException(403, Messages.ERR_ADMIN_USE_DIRECTORY);
                }

                if ("POST".equals(request.getMethod())) {
                    String expected = session == null ? null : (String) session.getAttribute("csrf");
                    String actual = request.getParameter("csrf");
                    if (expected == null
                            || actual == null
                            || !MessageDigest.isEqual(
                                    expected.getBytes(StandardCharsets.UTF_8),
                                    actual.getBytes(StandardCharsets.UTF_8))) {
                        throw new BusinessException(403, Messages.ERR_CSRF);
                    }
                    if ("/login".equals(path)
                            || "/password".equals(path)
                            || "/students/create".equals(path)
                            || "/students/reset".equals(path)) {
                        String username =
                                "/login".equals(path) ? request.getParameter("username") : identity.username();
                        // Only trust the transport peer. Caddy clients share its IP budget.
                        String key = limiter.check(request.getRemoteAddr(), username, path);
                        request.setAttribute("rateLimitKey", key);
                    }
                }

                Object flash = session == null ? null : session.getAttribute("flash");
                if (flash != null) {
                    request.setAttribute("flash", flash);
                    session.removeAttribute("flash");
                }

                chain.doFilter(request, response);

                String key = (String) request.getAttribute("rateLimitKey");
                if (key != null) {
                    String result = (String) request.getAttribute("rateLimitResult");
                    if ("success".equals(result)) {
                        limiter.recordSuccess(key);
                    } else if ("failure".equals(result)) {
                        limiter.recordFailure(key);
                    }
                }
            } catch (BusinessException e) {
                renderError(request, response, e.getStatus(), e.getMessage());
            } catch (Exception e) {
                LOG.error("Request failed ({})", e.getClass().getSimpleName());
                renderError(request, response, 500, Messages.ERR_INTERNAL);
            }
        } finally {
            MDC.remove(MDC_USER);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private Identity loadIdentityQuietly(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return null;
            }
            Identity previous = (Identity) session.getAttribute("identity");
            if (previous == null) {
                return null;
            }
            StudentService service =
                    (StudentService) request.getServletContext().getAttribute("studentService");
            return service == null ? null : service.current(previous);
        } catch (Exception e) {
            LOG.warn("Identity load failed on error dispatch: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void setSecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        // P4-5: only emit HSTS when running behind HTTPS. A long-lived HSTS
        // header on an HTTP-only deployment is a footgun because browsers
        // remember it and refuse the downgrade.
        if (cookieSecure(request)) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private boolean cookieSecure(HttpServletRequest request) {
        Object value = request.getServletContext().getAttribute("cookieSecure");
        return value instanceof Boolean && (Boolean) value;
    }

    private void renderError(HttpServletRequest req, HttpServletResponse res, int status, String message)
            throws IOException, ServletException {
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(status);
        if (status == 429) {
            res.setHeader("Retry-After", "60");
        }
        req.setAttribute("title", titleForStatus(status));
        req.setAttribute("error", message);
        req.getRequestDispatcher("/WEB-INF/views/error.jsp").forward(req, res);
    }

    private static String titleForStatus(int status) {
        return switch (status) {
            case 400 -> "请求无效";
            case 401 -> "请先登录";
            case 403 -> "无权访问";
            case 404 -> "页面不存在";
            case 410 -> "功能已停用";
            case 429 -> "请稍后重试";
            default -> "操作提示";
        };
    }
}
