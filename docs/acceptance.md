# 2026-09-20 优化实施与验收

本轮在 `main` 基线 `dcca644` 上修改，未提交到 Git、未配置远程仓库。源码在 D:\资料\studentManagerSix。
历史记录保存于 `acceptance-2026-09-08.md`；旧的 77/85 项 HTTP 报告不能代表本轮结果。

## 本次实际结果

| 验证 | 结果 |
|---|---|
| JDK 25 主源码、测试源码、WAR 构建 | 通过 |
| 单元测试 | 38 项，0 失败、0 错误、0 跳过 |
| 真实 MySQL 集成测试 | 11 项，0 失败、0 错误、0 跳过 |
| 外部 Compose 测试数据库路径 | 完整 verify 通过 |
| 自动 Testcontainers 数据库路径 | 完整 verify 通过；覆盖修正后的 MySQL 9 配置 |
| Spotless / Enforcer | 格式、版本、禁止 SNAPSHOT、依赖收敛均通过 |
| JaCoCo | 总行覆盖 86.10%；服务实现 100%；Passwords 94.44%；Validation 100% |
| SpotBugs + FindSecBugs | High 缺陷数 0，检查成功；工具输出有 invokedynamic 符号解析提示，不能据此保证不存在安全漏洞 |
| Dockerfile 实际镜像构建 | 通过；测试镜像 studentmanagersix-app:optimization-20260920 |
| 完整 HTTP 与重启 | 118 项通过，实际重启测试数据库与应用 |
| 业务清理与审计 | 回到 10 个学生、11 个账号，留下 8 条审计记录 |
| 旧库迁移 | 从仅有 001-schema 的有数据测试库升级；备份存在、学生/账号/标记保留、重复执行无变更 |
| 配置与资源 | Compose 三种组合/YAML/XML/Python 语法检查通过，资源内容指纹吻合 |
| 应用日志 | 最新测试容器日志无 ERROR/SEVERE/Exception 行，未发现测试密码或密码哈希 |

## 修复重点

1. 修复导入、void mock 和 Enforcer 规则错误；统一 Hibernate 间接依赖版本；显式选择兼容 JDK 25 的 formatter 和 JaCoCo。
2. PBKDF2-SHA256 恢复 600000 次；对低迭代历史密码原子升级，避免覆盖并发改密，保留旧密码登录兼容。
3. 独立 IP/规范化账号限流、有容量上限、哈希并发上限；不信任客户端伪造的转发 IP 头。
4. 取消身份缓存，下次请求即验证改密/重置/删除后的失效状态；未带 session 的 POST 不创建新 session。
5. 修复重置密码错误回填、翻页参数和 CSV 页码，明确导出当前页；保留 BOM 和公式前缀防护。
6. 测试按方法清理专用库，修正矛盾断言；新增 Service/Servlet/Filter/限流/密码策略回归。
7. 可重复迁移脚本先备份再补结构；随机初始配置脚本不覆盖现有 .env。
8. 格式化 Java/CSS/JS/JSP，刷新静态资源指纹；更新 CI、README、架构与方案说明。
9. 修复 Testcontainers 自带 MySQL 旧参数、Tomcat 未配置的 JNDI Realm；镜像依赖缓存只预取应用依赖。

## 环境与数据边界

本轮使用 `sms-opt-it`、`sms-opt-http`、`sms-opt-upgrade`、`sms-opt-upgrade-data` 独立测试项目，以及 Testcontainers 自动创建的临时数据库。
原 `studentmanagersix_student-data` 数据卷未迁移、未删除、未写入。`.env` 未修改。测试结束后只清理本轮隔离测试容器与测试卷，保留测试镜像和 Maven 缓存。

Docker Desktop 启动时发现两处残留 AF_UNIX socket。仅停止失败的 Docker 进程，并将 `Docker/run` 和 `docker-secrets-engine` 的零字节 socket 目录改名留存；没有重置 Docker 或删除镜像/数据卷。

## 复现与证据

- `mvn -B -ntp clean verify`：自动启动 Testcontainers MySQL。Windows 容器内运行时，Docker socket 映射与 TESTCONTAINERS_HOST_OVERRIDE 仅属于该测试环境。
- `docker compose -f compose.test.yaml up --abort-on-container-exit --exit-code-from tests`：使用 tmpfs 测试库；TEST_DB_URL 仅允许名称以 test_ 开始或 _test 结尾的专用库。
- `python scripts/smoke_test.py --restart`：在选定的 Compose 项目中执行完整 HTTP 与重启验收。
- `python scripts/migrate_database.py`：维护窗口内备份并升级已存在的演示数据库。
- 本次结果及日志位于 `docs/evidence/2026-09-20/`，当前 WAR 位于 `target/studentManagerSix.war`。

## 尚未声称完成的项目

- 全量 NVD/CVE 扫描：已配置独立每周/手动工作流，本次未执行完整依赖漏洞库扫描。无远程仓库，因此 GitHub Actions 未运行。
- HTTPS：叠加配置及数据卷身份已修正，配置语法通过，未进行真实证书/浏览器 HTTPS 验收。
- 未做浏览器截图与多视口视觉验收，未做大数据量/并发压力基准。
- 审计查询页、批量删除、完整弱口令词库、国际化、JSON/OpenAPI 属后续可选功能。
