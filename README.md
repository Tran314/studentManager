# 学生管理 SIX

保留 JSP → Servlet → Service → DAO → Hibernate → MySQL 架构的中文学生管理应用。当前定位为本机教学、作品展示和小型内部管理；默认仅监听 `127.0.0.1`。

## 启动

需要 Docker Desktop（Linux containers）和 Python 3.10+。首次在 PowerShell 执行：

```powershell
python scripts/configure_demo.py
docker compose up --build -d --wait
```

脚本生成随机密码并写入 `.env`，不打印密码、不覆盖已有配置。已有 `.env` 时直接使用原配置；不要重新复制覆盖。登录地址：`http://localhost:8080/studentManagerSix/`。管理员用户名为 `admin`，密码为 `.env` 的 `DEMO_ADMIN_PASSWORD`；演示学生用户名为 `1` 至 `10`，密码为 `DEMO_STUDENT_PASSWORD`。

也可以复制 `.env.example` 后自行填写四个密码。示例文件密码值为空，未配置时 Compose 会拒绝启动。密码生成建议使用 `secrets.token_urlsafe(24)`，避免原样粘贴包含 `$`、引号或空格的随机字符造成 dotenv 转义问题。

```powershell
docker compose ps
docker compose logs --tail 80 app
docker compose stop
docker compose start
```

数据库不暴露主机端口。`studentmanagersix_student-data` 保存正式演示数据。`docker compose down` 保留数据卷；需要保留数据时不可使用 `down -v`。

## 已有数据卷升级

`docker/init/*.sql` 只在新数据卷初始化时执行。升级旧部署时，先停止应用、保留数据库，再执行迁移：

```powershell
docker compose stop app
docker compose up -d db
python scripts/migrate_database.py
docker compose up --build -d --wait app
```

迁移脚本检查旧表，先用 mysqldump 在 `work/backups/` 生成备份，再补充缺失的姓名索引与审计表，并验证结果。密码只从数据库容器环境传给 MySQL 客户端，不出现在命令行。重复运行已完成迁移时不会重复建表/索引。它不覆盖学生、账号或密码，也不迁移 `legacy` 原始数据库。

在维护窗口内单独运行迁移，不要并发执行。MySQL DDL 不承诺整体事务回滚；中断后先保留备份、检查错误，脚本支持重试缺失步骤。若需回滚数据，先停止应用，在隔离库验证备份恢复后再操作真实数据卷。

## 功能与安全行为

- 管理员：创建学生及账号、详情、编辑、删除、搜索、每页 10/20/50 条、姓名/年龄/学号排序、导出当前页 CSV、重置学生密码。
- 学生：查看及编辑本人资料、修改本人密码。公开注册 `/register` 的 GET/POST 均返回 410。
- 搜索：学号精确匹配；ASCII 字母数字姓名词使用前缀匹配，中文/混合词和转义通配字符使用子串匹配。
- CSV 明确导出当前页，保留页码/筛选/排序，包含 UTF-8 BOM 与公式前缀防护。
- 重置密码失败保留目标学生，纠正后可重新提交。所有变更与审计记录处于同一数据库事务。
- 密码使用 PBKDF2-HMAC-SHA256、600,000 次迭代、独立随机盐。已有低迭代哈希在成功登录后原子升级，不覆盖并发改密；历史密码不因新弱口令规则被强制拒绝登录。
- 每次认证请求检查 `auth_version`，改密、重置、删除账号后旧会话在下次请求失效。保留一次身份查询以保证撤销语义，不使用 30 秒缓存。
- 登录旋转 Session ID；CSRF 保护所有 POST；会话 30 分钟空闲超时；安全头、JSP 输出转义和参数化查询保留。
- 限流：每个传输层 IP 每分钟最多 60 次敏感请求；每个规范化账号的登录/改密各 5 次/分钟，创建/重置各 20 次/分钟；15 分钟内累计 10 次失败锁定相应账号操作 15 分钟，成功清除失败历史。
- 限流最多保存 10,000 个键，容量满时拒绝新键；PBKDF2 最多同时执行 4 个，超出返回 429。限流为单进程状态，重启会清空，不能代替分布式部署的共享限流。
- 不信任任意 `X-Forwarded-For`。通过 Caddy 的请求共享代理 IP 配额；扩大部署规模前应设计可信代理和统一限额。

## 验证

本地 JDK 25 + Maven Wrapper：

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd clean verify -DskipITs
```

不安装本地 JDK 时可使用 `maven:3.9.16-eclipse-temurin-25` 容器构建。`mvn verify` 在未设置 `TEST_DB_URL` 时使用 Testcontainers 创建真实 MySQL，需要 Docker；不会静默跳过 IT。

```powershell
docker compose -f compose.test.yaml up --abort-on-container-exit --exit-code-from tests
docker compose -f compose.test.yaml down
python scripts/smoke_test.py --restart
```

Compose IT 使用独立 tmpfs MySQL，与正式数据卷分离；每个测试清理自身测试库夹具。**TEST_DB_URL 只可指向专用测试库。** HTTP 脚本创建、修改并清理临时学生，`--restart` 额外重启所选 Compose 项目的数据库和应用，不适合有其他用户操作时执行。自定义项目时同时设置 `COMPOSE_PROJECT_NAME`、相应 Compose 环境和 `TEST_BASE_URL`，避免重启其他项目。

HTTP 覆盖注册关闭、登录、CSRF、权限、分页、CSV、重置错误回填、立即撤销、限流、CRUD 与重启持久性。断言数量以每次 `target/http-acceptance.json` 为准，不使用旧报告固定数字。

门禁：Spotless、Enforcer（版本/禁止 SNAPSHOT/依赖收敛）、Surefire/Failsafe、SpotBugs + FindSecBugs High、JaCoCo 总行覆盖 ≥70%，Passwords/Validation/StudentServiceImpl 各 ≥85%。

独立依赖安全扫描：`mvn -Psecurity verify -DskipITs`。NVD API key 可通过 `NVD_API_KEY` 环境变量提供；无 key 或网络不可用时扫描可能限速/失败，不能视为通过。`.github/workflows/security.yml` 每周与手动执行该扫描，CVSS≥7 阻断；普通 CI 不声称已执行 dependency-check。仓库需配置 remote 并推送到 GitHub 后，Actions 才会运行。

## HTTPS

在 `certs/fullchain.pem` 与 `certs/privkey.pem` 放置有效证书后：

```powershell
docker compose -f compose.yaml -f compose.tls.yaml up -d --build --wait
```

该叠加配置沿用 `studentmanagersix` 项目及其数据卷，启用 Secure Cookie、HSTS 和 Caddy，访问 `https://localhost/studentManagerSix/`。证书由使用者提供，不自动申请证书；`certs/` 不纳入 Git 或镜像。测试自签名证书与正式受信证书验收应分别记录。

## 工程与维护

Java 25、Tomcat 11.0.25、Hibernate 7.4.7.Final、MySQL 9.7.1、Connector/J 9.7.0、Maven 3.9.16；工具版本以 `pom.xml` 为准。显式锁定了兼容 JDK 25 的 Palantir formatter 与 JaCoCo，间接依赖由 dependencyManagement 收敛。

`src/main` 为当前 WAR，`src/test` 为单元/集成测试，`docker/init` 为新库建表，`scripts` 为配置、迁移和验收工具。`legacy/` 保留原工程，不参与构建；`.env`、备份、证书和日志均忽略。

CSS/JS 为可读源码。修改静态文件后执行 `python scripts/fingerprint_assets.py` 刷新内容指纹和 JSP 引用。只读事务用于降低脏检查成本，仍会提交事务；姓名 B-tree 索引不加速前置通配的中文子串搜索。

本机 Tomcat 部署时须手动创建数据库并依次执行 `001-schema.sql`、`002-index.sql`、`003-audit.sql`；设置 DB_URL/DB_USER/DB_PASSWORD，以及需要演示数据时的 SEED_DEMO/DEMO_ADMIN_PASSWORD/DEMO_STUDENT_PASSWORD。Hibernate 只 validate，不自动改表。

审计记录已落库；审计查询页面、批量删除、完整弱口令库、国际化和 JSON/OpenAPI 仍为可选后续工作。当前验证记录见 `docs/acceptance.md`。
