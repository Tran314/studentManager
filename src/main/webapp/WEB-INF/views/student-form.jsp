<%@ page pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<div class="page-heading"><div><div class="eyebrow">STUDENT PROFILE</div><h1><c:out value="${title}"/></h1><p>完善基础信息，让每一次更新都有据可循。</p></div><a class="button quiet" href="${pageContext.request.contextPath}/">返回工作台 ↗</a></div>
<section class="card form-card"><div class="section-title"><span class="number-tag">01</span><div><h2>基础资料</h2><p class="muted">带 * 的项目为必填项。学号创建后不可修改。</p></div></div>
<form method="post">
<input type="hidden" name="csrf" value="${sessionScope.csrf}">
<div class="form-grid"><div><label for="sno">学号 *</label><input id="sno" name="sno" autocomplete="username" inputmode="numeric" pattern="[0-9]+" maxlength="${limits.snoDigits}" required ${creating ? '' : 'readonly'} value="<c:out value='${submitted and empty student ? param.sno : student.sno}'/>" placeholder="例如：20260001"></div>
<div><label for="sname">姓名 *</label><input id="sname" name="sname" maxlength="${limits.nameMax}" required value="<c:out value='${submitted ? param.sname : student.sname}'/>" placeholder="请输入学生姓名"></div>
<div><label for="age">年龄 *</label><input id="age" name="age" type="number" min="${limits.ageMin}" max="${limits.ageMax}" required value="<c:out value='${submitted ? param.age : (empty student ? 18 : student.age)}'/>"></div>
<div><label for="address">地址</label><input id="address" name="address" maxlength="${limits.addressMax}" value="<c:out value='${submitted ? param.address : student.address}'/>" placeholder="请输入地址（选填）"></div>
<c:if test="${creating}"><div class="full"><label for="password">初始密码 *</label><input id="password" name="password" type="password" minlength="${limits.passwordMin}" maxlength="${limits.passwordMax}" autocomplete="new-password" required placeholder="${limits.passwordMin}–${limits.passwordMax}个字符"><p class="field-help">学生可使用学号和此密码登录，登录后可修改密码。</p></div></c:if>
</div><div class="form-actions"><button class="primary" type="submit"><c:out value="${creating ? '创建学生账号' : '保存资料'}"/> →</button><a class="button quiet" href="${pageContext.request.contextPath}/">取消</a></div>
</form></section>
<%@ include file="footer.jspf" %>
