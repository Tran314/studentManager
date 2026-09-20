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
| DAO自己开启事务；查询不提交，删除嵌套事务 | Service每个操作一个Session/事务，统一提交、回滚、关闭。`tx()` 走读写事务，`txRead()` 走 `setDefaultReadOnly(true)`；Hibernate 配置 `provider_disables_autocommit=true` + Hikari `autoCommit=false` 跳过冗余 setAutoCommit |
| 异常吞掉、返回null或0导致空指针 | 业务异常携带状态和中文提示；系统错误日志记录类别；重复键按 MySQL 错误码 1062 映射 409，其余约束映射 400 |
| 登录仅跳转，任何人可进入管理功能 | Session Identity、账户有效性检查及管理员Filter；每次请求校验 auth_version；独立 IP/账号配额、有限容量与 PBKDF2 并发限制；公开注册关闭（410） |
| 密码CHAR(3)明文，还展示于列表 | 独立账户表，PBKDF2-SHA256（600000 次），页面无密码材料；登录时 `needsRehash` 透明升级到当前迭代数；`assertSafe` 拒绝常见弱口令；`assertNotEqualToLogin` 禁止密码=用户名 |
| Student.hbm.xml映射 | Jakarta Persistence 3.2注解映射 |
| JSP脚本与强制ArrayList转换 | request作用域、EL与转义标签 |
| 分页输入脆弱，无稳定排序及元数据 | PageResult、学号/姓名/年龄排序、搜索条件保留、完整前后页；可切页大小 10/20/50；CSV 导出（RFC 4180 + UTF-8 BOM + 公式注入防护） |
| GET也能删除，缺少CSRF | 变更仅POST，Session同步令牌 |
| 源码、字节码、依赖混放 | Maven标准目录和Docker多阶段构建 |
| 无审计追踪 | `audit_log` 表 + `AuditEntry` 实体；`AuditService` 与业务写入同事务；记录 CREATE/UPDATE/DELETE/PASSWORD_CHANGE/PASSWORD_RESET |
| 无结构化日志 | logback-classic + RollingFileAppender（每日 / 100MB / 14 天）；filter 生成 requestId 写入 MDC；认证后 MDC user |
| 静态资源无指纹 | `app.<sha8>.css` / `app.<sha8>.js` / `favicon.<sha8>.svg` + `Cache-Control: public, max-age=31536000, immutable` |
| 无 HTTP 压缩 | Tomcat Connector `compression="on"` + `compressibleMimeType` |
| 无 HTTPS 档位 | `compose.tls.yaml` Caddy 反代 + 条件性 `Strict-Transport-Security` |
| 单行压缩代码不可审查 | Spotless + `palantirJavaFormat`；`mvn spotless:check` 在 verify 阶段强制 |
| 测试塞进单个 `@Test` | 拆分独立测试，每个方法清理专用测试库夹具；Testcontainers `MySQLContainer` 自动启动；Mockito 覆盖 Filter/Servlet |

## 当前架构

~~~mermaid
flowchart TD
    Browser[浏览器 / 中文JSP页面] --> Filter[WebSecurityFilter\n编码 · 限流 · 懒Session · 角色 · CSRF · HSTS]
    Filter --> Servlet[StudentServlet\nRoute 路由表 · 参数解析 · 校验]
    Servlet --> Route[Route 枚举\npath / view / title / 行为]
    Servlet --> Limits[utils.Limits\n页大小 · 长度 · 密码策略]
    Servlet --> Messages[utils.Messages\nERR_*/TITLE_* 中文常量]
    Servlet --> Service[StudentServiceImpl\n业务逻辑 · tx/txRead · 审计同事务]
    Service --> StudentDao[StudentDao / Impl]
    Service --> AccountDao[AccountDao / Impl]
    Service --> Audit[AuditService\n同事务写 audit_log]
    Filter --> RateLimiter[utils.RateLimiter\n独立 IP / 账号配额 · 10000 键容量上限]
    StudentDao --> Hibernate[Hibernate Session]
    AccountDao --> Hibernate
    Audit --> Hibernate
    Hibernate --> Hikari[HikariCP\nautoCommit=false]
    Hikari --> MySQL[(MySQL 9.7.1\nstudent · account · audit_log · app_seed)]
    Servlet --> Views[WEB-INF/views/*.jsp\nEL + Jakarta Tags]
    Views --> Browser
    Listener[AppLifecycle] --> Factory[共享SessionFactory]
    Listener --> CtxAttrs[context attributes:\nstudentService · limits · cookieSecure]
    Factory --> Hibernate
~~~

`WebSecurityFilter` 现在依次完成：安全响应头（按 `cookieSecure` 条件发 HSTS）→ 静态资源长缓存 → `/health` → `/register` 永久 410 → ERROR 派发渲染错误页 → 惰性 Session → 实时身份/权限校验 → POST CSRF → 敏感请求限流 → 业务派发。所有路径上抛 `BusinessException` 时按状态码给对应中文标题（410/429/403/404 等）。

`StudentServlet` 的 GET 分发及错误回填使用 `Route` 枚举，POST 处理仍按路径调用对应处理方法。CSV 导出当前页；重置失败重新加载目标学生。

`AppLifecycle` 不再只注册 `studentService`：还把 `Limits.get()` 与 `cookieSecure` 布尔挂到 servlet context attribute，方便 JSP 用 EL 直接读。

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
    AUDIT_LOG {
        bigint id PK
        varchar actor_username
        varchar actor_role
        varchar action
        int target_sno
        varchar target_username
        varchar details
        datetime created_at
    }
    APP_SEED {
        int id PK
    }
~~~

新增 `audit_log` 表记录每个学生档案变更的"谁/何时/做了什么"。`audit_log.created_at` 由 DB 端 `CURRENT_TIMESTAMP(6)` 填充，避免客户端时钟漂移；`idx_audit_actor` / `idx_audit_target` / `idx_audit_created` 索引支持按操作者、按目标学号、按时间窗口的取证检索。

学生账号与学生档案一一关联，管理员无 `student_sno`。角色约束由数据库与服务器共同控制。学号创建后保持不变。删除按账户→学生顺序在同一事务执行，审计行同时落库（或随 tx 回滚——保证日志可信）。

密码格式为 `pbkdf2-sha256$v1$迭代次数$Base64盐$Base64哈希`。当前 `ITERATIONS=210_000`（OWASP 2024 PBKDF2-SHA256 建议下限）、16 字节随机盐、256 位派生结果、恒定时间比较。`verify()` 从密文解析迭代数；老 600k 哈希视为更强，自动保持。`needsRehash()` 仅当存储迭代数 < 210_000 时返回 true，登录成功后由 service 在独立写事务里 `Account.upgradeHash()` 升级。`assertSafe()` 拒绝约 30 条最常见弱口令（大小写不敏感），`assertNotEqualToLogin()` 禁止密码=学号/登录名。

## 路由与状态

| 路由 | 方法 | 权限 |
|---|---|---|
| `/` | GET | 按登录状态和角色跳转 |
| `/login` | GET/POST | 公共 |
| `/register` | GET | 410 Gone（公开注册永久关闭） |
| `/logout` | POST | 登录用户 |
| `/health` | GET | 公共，进程健康探针 |
| `/students` | GET | 管理员（支持 sno/name/sort/dir/size 分页查询参数） |
| `/students/export` | GET | 管理员（CSV 下载，RFC 4180） |
| `/students/detail` | GET | 管理员 |
| `/students/create` | GET/POST | 管理员 |
| `/students/edit` | GET/POST | 管理员 |
| `/students/reset` | GET/POST | 管理员（重置学生密码，强制踢出会话） |
| `/students/delete` | POST | 管理员 |
| `/profile` | GET/POST | 学生本人 |
| `/password` | GET/POST | 登录用户 |

校验错误 400，认证失败 401，角色/CSRF 失败 403，记录或页面不存在 404，重复学号 409，速率限制 429，内部错误 500；未登录访问业务页重定向到登录。`/`、`/login` 是 publicPath；`/students/*` 仅 admin；`/profile` 仅学生。搜索 `kw` 是纯 ASCII 字母数字时走前缀匹配（用 `idx_student_sname` 索引），否则（含中文或显式通配符）走子串匹配。页面输出通过 `<c:out>` 转义；所有资源来自本地，CSP 禁止内联脚本；静态资源 `Cache-Control: public, max-age=31536000, immutable`。

### 部署层

| 组件 | 角色 | 关键配置 |
|---|---|---|
| Tomcat 11.0.25 | 应用容器 | `compression="on"`，minSize 1024；以非 root 用户（uid 10001）运行；JVM `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |
| Caddy 2 | 可选 HTTPS 终止 | `compose.tls.yaml` overlay；读 `./certs/*.pem`；`Strict-Transport-Security` 在 TLS 路径上由 Caddy 注入；只在 `COOKIE_SECURE=true` 时由 app 注入 HSTS |
| MySQL 9.7.1 | 持久化 | 1 CPU / 512m；`student-data` 卷挂 `/var/lib/mysql`；init scripts `001-schema.sql`、`002-index.sql`、`003-audit.sql` |
| Maven 3.9.16 + Temurin JDK 25 | 构建 | 多阶段：`dependency:go-offline` 缓存层独立；`mvn -B -ntp package` |
| GitHub Actions ubuntu-latest | CI | 3 个串行 job：`build-and-static`（Spotless+SpotBugs+JaCoCo）→ `integration`（compose.test.yaml + Failsafe）→ `acceptance`（compose up + smoke_test.py） |

## 版本依据

- [Java 25 LTS支持路线](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Tomcat与Servlet/JSP版本对应](https://tomcat.apache.org/whichversion)
- [Hibernate 7.4官方兼容矩阵](https://hibernate.org/orm/releases/7.4/)
- [MySQL 9.7手册](https://dev.mysql.com/doc/refman/9.7/en/)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Maven发布下载](https://maven.apache.org/download.cgi)

实际依赖可从 Maven Central 解析。未采用预览版、Hibernate 8 开发版、Spring Boot、前后端分离或微服务。

## 2026-09-20 复核修复

旧数据卷通过 `python scripts/migrate_database.py` 备份并补齐增量结构；不会依赖 init 脚本自动重跑。认证撤销不再有 30 秒窗口；低迭代密码重哈希采用旧哈希条件更新，不覆盖并发改密。CI 常规门禁与独立每周依赖安全扫描分开，实际验证记录见 acceptance.md。
