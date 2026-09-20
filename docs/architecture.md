# 原技术栈解构与改造说明

## 原项目调用链

原页面把表单交给 LoginServlet、RegisterServlet、DeleteStudentServlet 和两个列表 Servlet。Servlet 直接创建 StudentServiceImpl，Service 固定创建 StudentDaoHibernateImpl，DAO 从 HibernateSessionFactoryUtil 获取全局 SessionFactory，再操作 student 表。结果通常写入 Session，重定向到包含 Java 脚本片段的 JSP。

旁边还残留一个 JDBC StudentDaoImpl 和 DBTool，但服务层实际选择 Hibernate 实现。JDBC 实现的 getStudent 返回 int，与 StudentDao 声明的 List<Student> 冲突，使全量源代码无法一致编译。

## 发现与对应改造

| 原问题 | 当前处理 |
|---|---|
| Eclipse Java 6、IDEA Java 15、绝对Tomcat路径冲突 | Maven统一Java 25和WAR结构；旧IDE配置留在legacy |
| WEB-INF/lib Hibernate 3与根lib Hibernate 5.4混用 | 单一Hibernate 7.4.7依赖图，旧JAR不进入构建 |
| HQL裸问号位置参数、旧save/delete API | 类型化HQL、命名参数、persist/remove |
| DAO自己开启事务；查询不提交，删除嵌套事务 | Service每个操作一个Session/事务，统一提交、回滚、关闭 |
| 异常吞掉、返回null或0导致空指针 | 业务异常携带状态和中文提示；系统错误日志记录类别 |
| 登录仅跳转，任何人可进入管理功能 | Session Identity、账户有效性检查及管理员Filter |
| 密码CHAR(3)明文，还展示于列表 | 独立账户表，PBKDF2-SHA256，页面无密码材料 |
| Student.hbm.xml映射 | Jakarta Persistence 3.2注解映射 |
| JSP脚本与强制ArrayList转换 | request作用域、EL与转义标签 |
| 分页输入脆弱，无稳定排序及元数据 | PageResult、学号排序、搜索条件保留、完整前后页 |
| GET也能删除，缺少CSRF | 变更仅POST，Session同步令牌 |
| 源码、字节码、依赖混放 | Maven标准目录和Docker多阶段构建 |

## 当前架构

~~~mermaid
flowchart TD
    Browser[浏览器 / 中文JSP页面] --> Filter[WebSecurityFilter\n编码 · 会话 · 角色 · CSRF]
    Filter --> Servlet[StudentServlet\n解析请求 · 校验 · 转发/重定向]
    Servlet --> Service[StudentServiceImpl\n业务逻辑 · 密码验证 · 事务]
    Service --> StudentDao[StudentDaoHibernateImpl]
    Service --> AccountDao[AccountDao]
    StudentDao --> Hibernate[Hibernate Session]
    AccountDao --> Hibernate
    Hibernate --> Hikari[HikariCP]
    Hikari --> MySQL[(MySQL 9.7.1)]
    Servlet --> Views[WEB-INF/views\nEL + Jakarta Tags]
    Views --> Browser
    Listener[AppLifecycle] --> Factory[共享SessionFactory]
    Factory --> Hibernate
~~~

AppLifecycle只在应用启动时创建SessionFactory和无请求状态的共享Service。请求事务里的Session不会跨线程共享。停止应用时关闭工厂和连接池。

GET查询的DTO或学生基础字段在事务内获取完整后再渲染，页面不依赖打开中的Session。学生列表放在request，不把全表放在Session。Session只保存Identity、CSRF和一次性提示。

## 数据关系

~~~mermaid
erDiagram
    STUDENT ||--o| ACCOUNT : student_sno
    STUDENT {
        int sno PK
        varchar sname
        int age
        varchar address
    }
    ACCOUNT {
        bigint id PK
        varchar username UK
        varchar password_hash
        varchar role
        int student_sno FK
        int auth_version
    }
    APP_SEED {
        int id PK
    }
~~~

学生账号与学生档案一一关联，管理员无student_sno。角色约束由数据库与服务器共同控制。学号创建后保持不变。删除按账户→学生顺序在同一事务执行，无孤儿记录。

密码格式为 pbkdf2-sha256$v1$迭代次数$Base64盐$Base64哈希。当前使用600000次迭代、16字节随机盐、256位派生结果和恒定时间比较。不同账户即使密码相同，存储值也不同。密码修改递增auth_version，每次请求重新确认版本和账户存在性。

## 路由与状态

| 路由 | 方法 | 权限 |
|---|---|---|
| / | GET | 按登录状态和角色跳转 |
| /login、/register | GET/POST | 公共 |
| /logout | POST | 登录用户 |
| /students、/students/detail | GET | 管理员 |
| /students/create、/students/edit | GET/POST | 管理员 |
| /students/delete | POST | 管理员 |
| /profile | GET/POST | 学生本人 |
| /password | GET/POST | 登录用户 |
| /health | GET | 公共，只返回健康状态 |

校验错误400，认证失败401，角色/CSRF失败403，记录或页面不存在404，重复学号409，内部错误500；未登录访问业务页重定向到登录。查询统一参数绑定，姓名中的%、_作为普通字符搜索。页面输出通过c:out转义；所有资源来自本地，CSP禁止内联脚本。

## 版本依据

- [Java 25 LTS支持路线](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Tomcat与Servlet/JSP版本对应](https://tomcat.apache.org/whichversion)
- [Hibernate 7.4官方兼容矩阵](https://hibernate.org/orm/releases/7.4/)
- [MySQL 9.7手册](https://dev.mysql.com/doc/refman/9.7/en/)
- [Maven发布下载](https://maven.apache.org/download.cgi)

实际依赖可从Maven Central解析，镜像已使用带补丁版本的官方标签验证。未采用预览版、Hibernate 8开发版、Spring Boot、前后端分离或微服务。

