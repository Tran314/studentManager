package com.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pojo.Identity;
import com.service.BusinessException;
import com.service.StudentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Negative-path tests for the security filter: redirect-on-anonymous,
 * 410-on-closed-registration, 403-on-bad-CSRF, 403-on-role-violation.
 * Successful paths are exercised by StudentServiceIT against a real
 * Tomcat / MySQL stack.
 */
@ExtendWith(MockitoExtension.class)
class WebSecurityFilterTest {

    @Mock HttpServletRequest req;
    @Mock HttpServletResponse res;
    @Mock HttpSession session;
    @Mock ServletContext ctx;
    @Mock StudentService service;
    @Mock FilterChain chain;
    @Mock RequestDispatcher dispatcher;

    private WebSecurityFilter filter;

    @BeforeEach
    void setup() throws Exception {
        filter = new WebSecurityFilter();
        when(req.getServletContext()).thenReturn(ctx);
        when(ctx.getAttribute("studentService")).thenReturn(service);
        when(req.getRequestDispatcher(anyString())).thenReturn(dispatcher);
    }

    @Test
    void anonymousRequestToProtectedRouteRedirectsToLogin() throws Exception {
        when(req.getServletPath()).thenReturn("/students");
        when(req.getSession(false)).thenReturn(null);

        filter.doFilter(req, res, chain);

        verify(res).sendRedirect(contains("/login"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void registerIsPermanentlyGone() throws Exception {
        when(req.getServletPath()).thenReturn("/register");

        filter.doFilter(req, res, chain);

        verify(res).setStatus(410);
        verify(req).setAttribute("title", "功能已停用");
        verify(dispatcher).forward(req, res);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        when(req.getServletPath()).thenReturn("/login");
        when(req.getMethod()).thenReturn("POST");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("csrf")).thenReturn("expected-token");
        when(req.getParameter("csrf")).thenReturn(null);

        filter.doFilter(req, res, chain);

        verify(res).setStatus(403);
        verify(dispatcher).forward(req, res);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void postWithMismatchedCsrfIsForbidden() throws Exception {
        when(req.getServletPath()).thenReturn("/login");
        when(req.getMethod()).thenReturn("POST");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("csrf")).thenReturn("expected-token");
        when(req.getParameter("csrf")).thenReturn("attacker-supplied-token");

        filter.doFilter(req, res, chain);

        verify(res).setStatus(403);
        verify(dispatcher).forward(req, res);
    }

    @Test
    void studentCannotListAdminDirectory() throws Exception {
        Identity student = new Identity(42L, "alice", "STUDENT", 100, 0);
        when(req.getServletPath()).thenReturn("/students");
        when(req.getMethod()).thenReturn("GET");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("csrf")).thenReturn("token");
        when(session.getAttribute("identity")).thenReturn(student);
        when(service.current(student)).thenReturn(student);

        filter.doFilter(req, res, chain);

        verify(res).setStatus(403);
        verify(dispatcher).forward(req, res);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminProfileIsForbidden() throws Exception {
        Identity admin = new Identity(1L, "admin", "ADMIN", null, 0);
        when(req.getServletPath()).thenReturn("/profile");
        when(req.getMethod()).thenReturn("GET");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("csrf")).thenReturn("token");
        when(session.getAttribute("identity")).thenReturn(admin);
        when(service.current(admin)).thenReturn(admin);

        filter.doFilter(req, res, chain);

        verify(res).setStatus(403);
        verify(dispatcher).forward(req, res);
    }

    @Test
    void errorDispatchSkipsAuthAndRateLimit() throws Exception {
        // ERROR dispatch arrives from web.xml error-page with no request body,
        // no session, and no POST. The filter must still set identity (so the
        // sidebar renders) and must not blow up on missing session attributes.
        when(req.getServletPath()).thenReturn("/something/missing");
        AtomicReference<Object> identityAttr = new AtomicReference<>();
        when(req.setAttribute(anyString(), any())).thenAnswer(inv -> {
            if ("identity".equals(inv.getArgument(0))) {
                identityAttr.set(inv.getArgument(1));
            }
            return null;
        });
        when(req.getAttribute("jakarta.servlet.error.request_uri")).thenReturn("/students/404-source");
        when(req.getSession(false)).thenReturn(null);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
