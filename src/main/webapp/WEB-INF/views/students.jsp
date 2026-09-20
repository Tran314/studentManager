<%@ page pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<div class="page-heading"><div><div class="eyebrow">STUDENT DIRECTORY</div><h1>学生管理</h1><p>学生资料一目了然，管理工作从容有序。</p></div><a class="button primary" href="${pageContext.request.contextPath}/students/create">＋ 新增学生</a></div>
<div class="stats"><div class="stat card"><span>查询记录</span><strong><c:out value="${result.totalRows}"/><small>位学生</small></strong></div><div class="stat card"><span>当前页码</span><strong><c:out value="${result.page}"/><small>/ <c:out value="${result.totalPages}"/> 页</small></strong></div><div class="stat card accent"><span>资料管理</span><strong class="text-stat">清晰 · 有序</strong><small>按学号升序排列，每页<c:out value="${limits.pageSize}"/>条</small></div></div>
<section class="card directory"><div class="table-heading"><div><h2>学生档案</h2><p class="muted">查找、查看并维护学生的基础信息</p></div><span class="pill">DIRECTORY</span></div>
<form class="search-form" action="${pageContext.request.contextPath}/students" method="get">
<div><label for="search-sno">学号</label><input id="search-sno" name="sno" inputmode="numeric" maxlength="${limits.snoDigits}" value="<c:out value='${param.sno}'/>" placeholder="精确查找学号"></div>
<div><label for="search-name">姓名</label><input id="search-name" name="name" maxlength="${limits.nameMax}" value="<c:out value='${param.name}'/>" placeholder="输入姓名关键词"></div><button type="submit" class="primary">搜索</button><a class="button quiet" href="${pageContext.request.contextPath}/students">重置</a>
</form>
<div class="table-scroll"><table><thead><tr><th>学号</th><th>学生姓名</th><th>年龄</th><th>地址</th><th class="align-right">操作</th></tr></thead><tbody>
<c:forEach items="${result.items}" var="s"><tr><td class="mono"><c:out value="${s.sno}"/></td><td><div class="student-name"><span class="student-avatar">学</span><strong><c:out value="${s.sname}"/></strong></div></td><td><c:out value="${s.age}"/> 岁</td><td class="address-cell"><c:out value="${empty s.address ? '—' : s.address}"/></td>
<td><div class="row-actions"><a href="${pageContext.request.contextPath}/students/detail?sno=${s.sno}">详情</a><a href="${pageContext.request.contextPath}/students/edit?sno=${s.sno}">编辑</a><form action="${pageContext.request.contextPath}/students/delete" method="post" data-confirm="删除后，该学生及其登录账号将一并移除。确定删除？"><input type="hidden" name="csrf" value="${sessionScope.csrf}"><input type="hidden" name="sno" value="${s.sno}"><button class="danger-link" type="submit">删除</button></form></div></td></tr></c:forEach>
<c:if test="${empty result.items}"><tr><td colspan="5"><div class="empty-state"><span>◎</span><h3>暂无匹配的学生</h3><p>试试其他搜索条件，或新增一份学生档案。</p></div></td></tr></c:if>
</tbody></table></div>
<c:url var="previousUrl" value="/students"><c:param name="page" value="${result.page - 1}"/><c:param name="sno" value="${param.sno}"/><c:param name="name" value="${param.name}"/></c:url>
<c:url var="nextUrl" value="/students"><c:param name="page" value="${result.page + 1}"/><c:param name="sno" value="${param.sno}"/><c:param name="name" value="${param.name}"/></c:url>
<div class="pagination"><span>共 <c:out value="${result.totalRows}"/> 条 · 第 <c:out value="${result.page}"/> / <c:out value="${result.totalPages}"/> 页</span><div>
<c:choose><c:when test="${result.page > 1}"><a class="button quiet" href="<c:out value='${previousUrl}'/>">← 上一页</a></c:when><c:otherwise><button disabled>← 上一页</button></c:otherwise></c:choose>
<c:choose><c:when test="${result.page < result.totalPages}"><a class="button quiet" href="<c:out value='${nextUrl}'/>">下一页 →</a></c:when><c:otherwise><button disabled>下一页 →</button></c:otherwise></c:choose>
</div></div></section>
<%@ include file="footer.jspf" %>

