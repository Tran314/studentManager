<%@ page pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="title" value="服务器错误"/>
<%@ include file="header.jspf" %>
<section class="card empty-state">
    <span>500</span>
    <h1>暂时无法完成操作</h1>
    <p>请稍后重试。</p>
    <a class="button primary" href="${pageContext.request.contextPath}/">返回工作台</a>
</section>
<%@ include file="footer.jspf" %>
