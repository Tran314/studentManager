<%@ page pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="title" value="页面不存在"/>
<%@ include file="header.jspf" %>
<section class="card empty-state">
    <span>404</span>
    <h1>页面不存在</h1>
    <p>请检查地址，或返回工作台。</p>
    <a class="button primary" href="${pageContext.request.contextPath}/">返回工作台</a>
</section>
<%@ include file="footer.jspf" %>
