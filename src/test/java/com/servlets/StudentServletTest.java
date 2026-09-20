package com.servlets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pojo.*;
import com.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StudentServletTest {
    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpServletResponse res = mock(HttpServletResponse.class);
    private final HttpSession session = mock(HttpSession.class);
    private final ServletContext context = mock(ServletContext.class);
    private final StudentService service = mock(StudentService.class);
    private final RequestDispatcher dispatcher = mock(RequestDispatcher.class);
    private final Map<String, Object> attrs = new HashMap<>();
    private final StringWriter body = new StringWriter();
    private final StudentServlet servlet = new StudentServlet();
    private final Identity admin = new Identity(1, "admin", "ADMIN", null, 0);
    private final Identity studentIdentity = new Identity(2, "42", "STUDENT", 42, 0);
    private final Student student = new Student(42, "张三", 20, "香港");

    @BeforeEach
    void setup() throws Exception {
        ServletConfig config = mock(ServletConfig.class);
        when(config.getServletContext()).thenReturn(context);
        when(context.getAttribute("studentService")).thenReturn(service);
        servlet.init(config);
        when(req.getContextPath()).thenReturn("/app");
        when(req.getSession()).thenReturn(session);
        when(req.getRequestDispatcher(anyString())).thenReturn(dispatcher);
        when(req.getAttribute(anyString())).thenAnswer(i -> attrs.get(i.getArgument(0)));
        doAnswer(i -> {
                    attrs.put(i.getArgument(0), i.getArgument(1));
                    return null;
                })
                .when(req)
                .setAttribute(anyString(), any());
        when(res.getWriter()).thenReturn(new PrintWriter(body));
        when(req.getParameter("sno")).thenReturn("42");
        when(service.find(42)).thenReturn(student);
    }

    private void get(String path) throws Exception {
        when(req.getServletPath()).thenReturn(path);
        servlet.doGet(req, res);
    }

    private void post(String path) throws Exception {
        when(req.getServletPath()).thenReturn(path);
        servlet.doPost(req, res);
    }

    @Test
    void homeAndHealthUseCorrectTargets() throws Exception {
        get("");
        verify(res).sendRedirect("/app/login");
        attrs.put("identity", admin);
        get("/");
        verify(res).sendRedirect("/app/students");
        attrs.put("identity", studentIdentity);
        get("");
        verify(res).sendRedirect("/app/profile");
        when(service.healthy()).thenReturn(true);
        get("/health");
        verify(res).setStatus(200);
        when(service.healthy()).thenThrow(new IllegalStateException());
        get("/health");
        verify(res).setStatus(503);
    }

    @Test
    void viewsLoadDataAndRejectPostOnlyRoutes() throws Exception {
        for (String path : List.of(
                "/login", "/password", "/students/create", "/students/detail", "/students/edit", "/students/reset")) {
            get(path);
        }
        assertSame(student, attrs.get("student"));
        attrs.put("identity", studentIdentity);
        get("/profile");
        get("/logout");
        get("/does-not-exist");
        verify(res, times(2)).setStatus(405);
        post("/students");
        verify(res).setHeader("Allow", "GET");
    }

    @Test
    void csvUsesCurrentPageAndEscapesExcelContent() throws Exception {
        when(req.getParameter("page")).thenReturn("2");
        when(req.getParameter("size")).thenReturn("20");
        when(req.getParameter("sort")).thenReturn("name");
        when(req.getParameter("dir")).thenReturn("desc");
        when(service.search(42, "", 2, 20, "name", true))
                .thenReturn(
                        new PageResult<>(List.of(new Student(42, "=1+1", 20, "hello,\"world\"\nnext")), 2, 20, 21, 2));
        get("/students/export");
        assertTrue(body.toString().startsWith("\uFEFF学号"));
        assertTrue(body.toString().contains("'=1+1"));
        assertTrue(body.toString().contains("\"hello,\"\"world\"\"\nnext\""));
        verify(service).search(42, "", 2, 20, "name", true);
    }

    @Test
    void directoryValidatesPageSizesAndSearch() throws Exception {
        when(req.getParameter("sno")).thenReturn("");
        for (String size : Arrays.asList(null, "20", "50", "garbage")) {
            when(req.getParameter("size")).thenReturn(size);
            get("/students");
        }
        verify(service, times(2)).search(null, "", 1, 10, null, false);
        verify(service).search(null, "", 1, 20, null, false);
        verify(service).search(null, "", 1, 50, null, false);
    }

    @Test
    void resetErrorsRetainStudentAndCanBeResubmitted() throws Exception {
        attrs.put("identity", admin);
        when(req.getParameter("newPassword")).thenReturn("Fresh-secret-2026");
        when(req.getParameter("confirmPassword")).thenReturn("wrong");
        post("/students/reset");
        verify(res).setStatus(400);
        assertSame(student, attrs.get("student"));
        verify(service, never()).resetPassword(any(), anyInt(), any());
        when(req.getParameter("confirmPassword")).thenReturn("Fresh-secret-2026");
        post("/students/reset");
        verify(service).resetPassword(admin, 42, "Fresh-secret-2026");
        verify(res).sendRedirect("/app/students");
    }

    @Test
    void loginRotatesSessionAndPreservesFailureStatus() throws Exception {
        when(req.getParameter("username")).thenReturn("admin");
        when(req.getParameter("password")).thenReturn("secret");
        when(service.login("admin", "secret")).thenReturn(admin);
        post("/login");
        verify(req).changeSessionId();
        verify(session).setAttribute("identity", admin);
        when(service.login("admin", "secret")).thenThrow(new BusinessException(401, "bad"));
        post("/login");
        verify(res).setStatus(401);
        assertEquals("failure", attrs.get("rateLimitResult"));
        post("/logout");
        verify(session).invalidate();
    }

    @Test
    void mutationsUseIdentityForProfileAndHandleFormErrors() throws Exception {
        attrs.put("identity", admin);
        when(req.getParameter("sname")).thenReturn("name");
        when(req.getParameter("age")).thenReturn("20");
        when(req.getParameter("address")).thenReturn("addr");
        when(req.getParameter("password")).thenReturn("secret");
        post("/students/create");
        verify(service).register(42, "name", "secret", 20, "addr", admin);
        post("/students/edit");
        verify(service).update(42, "name", 20, "addr", admin);
        attrs.put("identity", studentIdentity);
        when(req.getParameter("sno")).thenReturn("999");
        post("/profile");
        verify(service).update(42, "name", 20, "addr", studentIdentity);
        when(req.getParameter("age")).thenReturn("151");
        post("/profile");
        verify(res).setStatus(400);
        assertSame(student, attrs.get("student"));
        when(req.getParameter("sno")).thenReturn("42");
        attrs.put("identity", admin);
        post("/students/delete");
        verify(service).delete(42, admin);
    }

    @Test
    void passwordMismatchAndSuccessHandleSessionCorrectly() throws Exception {
        attrs.put("identity", studentIdentity);
        when(req.getParameter("newPassword")).thenReturn("Fresh-secret-2026");
        when(req.getParameter("confirmPassword")).thenReturn("different");
        post("/password");
        verify(res).setStatus(400);
        verify(session, never()).invalidate();
        when(req.getParameter("confirmPassword")).thenReturn("Fresh-secret-2026");
        when(req.getParameter("oldPassword")).thenReturn("Old-secret-2026");
        post("/password");
        verify(service).changePassword(studentIdentity, "Old-secret-2026", "Fresh-secret-2026");
        verify(session).invalidate();
        verify(res).sendRedirect("/app/login");
    }
}
