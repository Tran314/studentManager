<%@ page pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<section class="auth-layout">
<div class="auth-intro">
<div class="eyebrow">YOUR CAMPUS, CONNECTED</div>
<h1>每一份资料，<br>都值得妥善管理。</h1>
<p>从这里开始，连接你的校园生活。<br>学生档案与个人资料，一个工作台即可完成。</p>
<div class="intro-grid">
<span>01 <strong>有序管理</strong>
</span>
<span>02 <strong>随时更新</strong>
</span>
<span>03 <strong>安全访问</strong>
</span>
</div>
<div class="abstract-card">
<div class="abstract-line">
</div>
<div class="abstract-line short">
</div>
<div class="abstract-circles">
<i>S</i>
<i>I</i>
<i>X</i>
</div>
<span>专注于每一位学生</span>
</div>
</div>
<div class="card auth-card">
<div class="eyebrow">WELCOME BACK</div>
<h2>欢迎回来</h2>
<p class="muted">请输入账号信息，进入你的工作空间。</p>
<form method="post" action="${pageContext.request.contextPath}/login">
<input type="hidden" name="csrf" value="${sessionScope.csrf}">
<label for="username">登录名 / 学号</label>
<input id="username" name="username" maxlength="${limits.usernameMax}" autocomplete="username" required value="<c:out value='${param.username}'/>" placeholder="学生请输入学号">
<label for="password">密码</label>
<input id="password" name="password" type="password" maxlength="${limits.passwordMax}" autocomplete="current-password" required placeholder="请输入密码">
<button class="primary wide" type="submit">登录工作台 <span>→</span>
</button>
</form>
<p class="auth-switch">还没有学生账号？请联系管理员创建。</p>
</div>
</section>
<%@ include file="footer.jspf" %>

