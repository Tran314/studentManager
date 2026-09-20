<%@ page pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<div class="page-heading"><div><div class="eyebrow">ACCOUNT SECURITY</div><h1>修改密码</h1><p>更新后，所有已登录会话都会失效，请重新登录。</p></div></div>
<section class="card form-card compact"><h2>设置新密码</h2><form method="post">
<input type="hidden" name="csrf" value="${sessionScope.csrf}">
<label for="oldPassword">当前密码</label><input id="oldPassword" name="oldPassword" type="password" required maxlength="${limits.passwordMax}" autocomplete="current-password">
<label for="newPassword">新密码</label><input id="newPassword" name="newPassword" type="password" required minlength="${limits.passwordMin}" maxlength="${limits.passwordMax}" autocomplete="new-password">
<label for="confirmPassword">确认新密码</label><input id="confirmPassword" name="confirmPassword" type="password" required minlength="${limits.passwordMin}" maxlength="${limits.passwordMax}" autocomplete="new-password">
<p class="field-help">使用<c:out value="${limits.passwordMin}"/>–<c:out value="${limits.passwordMax}"/>个字符，建议组合字母、数字和符号。</p>
<div class="form-actions"><button class="primary" type="submit">更新密码 →</button></div></form></section>
<%@ include file="footer.jspf" %>

