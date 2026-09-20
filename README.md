# 学生管理 SIX

保留 JSP → Servlet → Service → DAO → Hibernate → MySQL 的学生管理项目，提供中文管理界面、管理员/学生权限、学生增删改查、搜索分页和个人资料维护。

## 一键启动（Windows / Docker Desktop）

1. 启动 Docker Desktop，使用 Linux containers。
2. 在本项目目录打开 PowerShell，首次配置：

~~~powershell
Copy-Item .env.example .env
# 可编辑 .env，修改演示密码或 APP_PORT。
docker compose up --build -d
docker compose ps
~~~

3. 等待 db 和 app 显示 healthy，然后打开 <http://localhost:8080/studentManagerSix/>。

本次改造已经创建了本机 .env。源码包只提供 .env.example，解压后按上面的步骤复制即可。首次运行需要联网下载镜像和 Maven 依赖，之后构建会复用缓存。

| 账号类型 | 登录名 | 初次默认密码 |
|---|---|---|
| 管理员 | admin | Admin-Demo-2026 |
| 示例学生 | 1 至 10 | Student-Demo-2026 |

密码以 **.env 中首次初始化的设置**为准，后续编辑 .env 不会重置已有用户密码。以上仅为本机演示默认值。

~~~powershell
docker compose logs --tail 80 app    # 应用日志
docker compose stop                 # 停止服务，保留数据
docker compose start                # 恢复服务
docker compose down                 # 移除本项目容器及网络，保留数据卷
~~~

项目不对主机暴露数据库端口；网页仅监听本机 127.0.0.1。若修改 APP_PORT，访问相应端口。已有数据保存在 Compose 的 student-data 卷中，不要在保留数据时使用 down -v。

## 功能与权限

- 管理员：新增学生（同时创建登录账号）、学生详情、编辑资料、删除学生及其账号、按学号精确搜索、按姓名关键词搜索、每页10条分页。
- 学生：公开注册、学号登录、查看与编辑本人姓名/年龄/地址、修改密码。
- 双方：修改密码、退出登录。管理员账号不属于学生档案，不能从学生删除入口删除。
- 学号为1–2147483647的整数，创建后不可修改；姓名1–20字、年龄1–150、地址不超过50字，密码8–128个字符。
- 无匹配数据时展示空状态；负页码调整为1，超过末页时回到末页，非整数页码返回400。
- 注册不接受角色选择；学生无法通过修改请求参数获取他人资料。
- 密码变更通过账户 auth_version 使所有旧会话失效；删除账户后旧会话也立即失效。
- 登录成功变更 Session ID，30分钟无访问后过期；修改操作仅接受带 CSRF 令牌的 POST。

## 技术栈与工程结构

| 技术 | 固定版本 |
|---|---|
| Java | 25（构建与运行镜像当前内置 Temurin 25.0.4） |
| Maven / Wrapper | 3.9.16 / 3.3.4 |
| Tomcat | 11.0.25 |
| Servlet / JSP / Jakarta Tags | 6.1 / 4.0 / 3.0 |
| Hibernate ORM / Persistence | 7.4.7.Final / 3.2 |
| MySQL / Connector/J | 9.7.1 / 9.7.0 |
| JUnit Jupiter | 6.1.3 |

~~~text
src/main/java/com/
  servlets/   HTTP请求与页面路由
  service/    业务、密码验证、事务边界
  dao/        参数化HQL与实体持久化
  pojo/       Student、Account、Identity、PageResult
  utils/      生命周期、Hibernate、校验、密码哈希、Filter
src/main/webapp/
  WEB-INF/views/   JSP页面（不可直接请求）
  assets/          本地CSS与JavaScript
src/test/          单元测试、真实MySQL集成测试
docker/init/       仅供新建演示库使用的建表SQL
scripts/           HTTP验收脚本
docs/              技术解构与验收说明
legacy/            本机保留的旧工程，不参与编译、打包或Docker构建
~~~

完整解构及架构图见 [docs/architecture.md](docs/architecture.md)。

## Maven / IDEA / 本机 Tomcat

Docker 构建无需在本机安装 JDK。若要在 IDEA 运行和调试：

1. 安装 JDK 25，在 IDEA 打开 pom.xml 并导入 Maven 项目，Project SDK 设为25。
2. 用新版 Maven 工程配置，不导入 legacy 内的 .iml、.classpath 或 .idea。
3. PowerShell 运行：

~~~powershell
.\mvnw.cmd clean package
~~~

生成 target/studentManagerSix.war；将其部署到 Tomcat 11.0.25 的 webapps 目录。Unix/macOS 可使用 ./mvnw clean package。

本机 MySQL 9.7.1 应先新建独立数据库和应用账户，选择该数据库后执行 docker/init/001-schema.sql；不要对旧 studentManager 数据库直接执行新脚本。Tomcat 启动环境配置：

~~~text
DB_URL=jdbc:mysql://localhost:3306/student_manager?connectionTimeZone=UTC
DB_USER=student_app
DB_PASSWORD=你的数据库密码
SEED_DEMO=true
DEMO_ADMIN_PASSWORD=至少8个字符的管理员密码
DEMO_STUDENT_PASSWORD=至少8个字符的学生密码
COOKIE_SECURE=false
~~~

Servlet/JSP API 标记为 provided，不装入 WAR。Hibernate 在启动时执行 validate，不自动创建或修改表。正式 HTTPS 接入时把 COOKIE_SECURE 设置为 true；当前交付定位为本机课程/作品演示。

## 验证

### 单元测试

~~~powershell
.\mvnw.cmd test
~~~

### 隔离的真实 MySQL 集成测试

~~~powershell
docker compose -f compose.test.yaml up --abort-on-container-exit --exit-code-from tests
docker compose -f compose.test.yaml down
~~~

测试使用 MySQL 9.7.1，独立临时数据库，不访问演示数据卷。每次运行前 down 移除旧测试容器，确保临时数据库重新初始化。Maven 缓存保留以加速再次执行。

单元报告在 target/surefire-reports，集成报告在 target/failsafe-reports。普通 mvn verify 若未设置 TEST_DB_URL，会明确跳过真实数据库集成测试；以上 Compose 命令会启用它。

### 页面、权限与重启验收

应用启动后，使用 Python 3.10+：

~~~powershell
python scripts/smoke_test.py
python scripts/smoke_test.py --restart
~~~

脚本从 .env 读取管理员密码，创建并清理自己的临时学生记录，覆盖完整HTTP流程、CSRF、越权、HTML转义、Cookie、登录退出和改密。--restart 额外依次重启本项目数据库和应用并确认记录/密码保留，因此执行期间网页会短暂中断。报告输出到 target/http-acceptance.json。

实际验收结果见 [docs/acceptance.md](docs/acceptance.md)。

## 数据初始化与原工程

- 数据库容器只在新数据卷上执行建表SQL。
- 应用使用一个事务创建 admin 和原 SQL 的十条学生资料；每个账户单独生成随机盐。
- app_seed 标记与演示账号在同一事务写入，成功后不重复导入，不覆盖修改后的资料或密码。
- 原始源码与JAR保存在本机 legacy 目录，另有完整原始ZIP快照。源码发行包不包含旧依赖及个人IDE设置。
- 本次改造不迁移原库和三位明文密码；旧 Servlet/JSP URL 已由新路由替代。

