<%@ page pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<div class="page-heading"><div><div class="eyebrow">STUDENT RECORD</div><h1>学生详情</h1><p>查看学生的基础档案。</p></div><a class="button quiet" href="${pageContext.request.contextPath}/students">返回列表 ↗</a></div>
<section class="card form-card"><div class="section-title"><span class="avatar large">学</span><div><h2><c:out value="${student.sname}"/></h2><span class="muted">学号 <c:out value="${student.sno}"/></span></div></div>
<dl class="details"><div><dt>学生姓名</dt><dd><c:out value="${student.sname}"/></dd></div><div><dt>年龄</dt><dd><c:out value="${student.age}"/> 岁</dd></div><div class="full"><dt>地址</dt><dd><c:out value="${empty student.address ? '未填写' : student.address}"/></dd></div></dl>
<div class="form-actions"><a class="button primary" href="${pageContext.request.contextPath}/students/edit?sno=${student.sno}">编辑资料 →</a></div></section>
<%@ include file="footer.jspf" %>

