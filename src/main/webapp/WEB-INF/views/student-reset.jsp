<%@ page pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ include file="header.jspf" %>
<div class="page-heading">
<div>
<div class="eyebrow">ADMIN ACTION</div>
<h1>重置学生密码</h1>
<p>学号 <c:out value="${student.sno}"/> · <c:out value="${student.sname}"/>
</p>
</div>
<a class="button quiet" href="${pageContext.request.contextPath}/students">返回列表 ↗</a>
</div>
<section class="card form-card compact">
<h2>设置新密码</h2>
<form method="post" action="${pageContext.request.contextPath}/students/reset?sno=<c:out value='${student.sno}'/>">
<input type="hidden" name="csrf" value="${sessionScope.csrf}">
<input type="hidden" name="sno" value="<c:out value='${student.sno}'/>">
<label for="newPassword">新密码</label>
<input id="newPassword" name="newPassword" type="password" required minlength="${limits.passwordMin}" maxlength="${limits.passwordMax}" autocomplete="new-password">
<label for="confirmPassword">确认新密码</label>
<input id="confirmPassword" name="confirmPassword" type="password" required minlength="${limits.passwordMin}" maxlength="${limits.passwordMax}" autocomplete="new-password">
<p class="field-help">使用<c:out value="${limits.passwordMin}"/>–<c:out value="${limits.passwordMax}"/>个字符。重置后该学生的所有会话将立即失效，操作会写入审计日志。</p>
<div class="form-actions">
<button class="primary" type="submit">重置密码 →</button>
<a class="button quiet" href="${pageContext.request.contextPath}/students">取消</a>
</div>
</form>
</section>
<%@ include file="footer.jspf" %>
