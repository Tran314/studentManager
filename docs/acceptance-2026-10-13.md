# 验收记录（latest）

> 学生在做的事：把每一次验收的"环境 / 命令 / 结果 / 限制"如实记下来。
> 不做完整 CVE 扫描、不做生产压测、不做真实 HTTPS 证书校验。
> 任何无法在 5 分钟内复现的结论都不写。

---

## 验收窗口 2026-10-13 (dependabot 大批清理)

**目的**：清理长期积压的 8 个 dependabot PR，验证合并后无回归；与 PR #9 基线（2026-10-06 e803c14）做差分。

**环境**
- 起点 commit: `e803c145adb670f71e71be4196ac403d7edc9e17` (main, PR #9 合并)
- 终点 commit: `4c92c2a` (dependabot 清理后 main)
- 工具: `gh` CLI（设备流登录，Token 缺 `workflow` scope）
- 操作模式: GitHub REST API（仓库未 clone 到本地）

**本窗口自动 / 人工验证（与 PR #9 基线对比）**

| 项 | PR #9 基线 | 本窗口结果 | 结论 |
|---|---|---|---|
| LICENSE | 缺失 | **新增 MIT**（commit `a2abfa9`） | ✅ |
| Open dependabot PR | 8 | **2 留待人工 + 1 待评估 + 5 已合** | 显著减少 |
| `pom.xml` mockito | 5.18.0 | 5.23.0（PR #5 已合） | ✅ patch |
| `pom.xml` spotbugs | 4.9.6.0 | 4.10.4.1（PR #6 已合） | ✅ patch |
| `pom.xml` enforcer | 3.6.1 | 3.6.3（PR #7 已合，dependabot 已自动 rebase） | ✅ patch |
| `pom.xml` dependency-check | 12.1.1 | 13.0.0（PR #2 已合） | ✅ minor |
| `pom.xml` spotless | 2.46.1 | 2.46.1（PR #8 留待评估，见下） | 待评估 |
| `actions/upload-artifact` | 4 | 7（PR #1 已合） | ✅ major 但无破坏 |
| `actions/cache` | 4 | 4（PR #3 待用户手动合） | 阻塞：gh 无 workflow scope |
| `actions/checkout` | 4 | 4（PR #4 待用户手动合） | 阻塞：gh 无 workflow scope |

**已合 PR**
- #1 `actions/upload-artifact 4→7`
- #2 `dependency-check 12.1.1→13.0.0`
- #5 `mockito 5.18.0→5.23.0`
- #6 `spotbugs 4.9.6.0→4.10.4.1`
- #7 `enforcer 3.6.1→3.6.3`

**待用户手动合并（gh token 缺 `workflow` scope，无法用 API 改 workflow 文件）**
- #3 `actions/cache 4→6`
- #4 `actions/checkout 4→7`
  - 注：v7 默认 `persist-credentials: false`，对仅做 checkout + 构建 + 测试的工作流无影响。

**待评估（breaking 风险）**
- #8 `spotless 2.46.1→3.10.2`
  - spotless 3.0+ 要求 JDK 17+（本项目 JDK 25 ✅）
  - 默认 google-java-format 由 1.28 升到 1.30（JVM 21+），可能触发仓库内 Java 源文件大改
  - 建议：在本地运行 `mvn spotless:check`，观察是否有需要重排的源文件
  - 风险收益：spotless 3.10 修复了多个 stability bug，建议合并但需配合一次 PR 风格的格式化提交

**自动测试**
- 本窗口未运行 `mvn verify` / `docker compose up`。
- 合并项均为版本号 bump + plugin 升级，预期零行为变化；待 `mvn verify` 在 6 个新依赖版本上重跑。
- PR #9 的"40 + 22 + 124"基线在没有源码修改的情况下仍可作为参考。

**已知未做项（保持原状）**
- 未运行最新依赖下的 `mvn verify`。
- 未对 PR #9 涉及的源文件做 6 个 dependabot 升级后的二次回归。
- 未做 Tomcat 真实证书下的 HTTPS 验收（Caddy 在前做 TLS 终止是预期部署形态）。
- 未做压测 / 限流器在多实例下的协同验证。
- 弱口令词库 31 条 → top-10k 全量仍未做。
- `StudentDaoHibernateImpl.search()` 的 DAO 直接单测仍未补（见 `audit-2026-10-13/StudentDaoIT.java` 草稿）。

**结论**
- 本窗口是"项目治理清扫"，不是"功能验收"。
- 在 #3 #4 手动合上 + #8 评估决定之前，本仓库的 main 仍处于"包含部分未合并升级"的状态。
- 下一次"完整 HTTP 验收"应在 #3 #4 #8 全部合并后做，统计行覆盖 / 集成测试用例数 / 静态分析告警数与 PR #9 基线的差分。

---

## 验收窗口 2026-10-06 (PR #9 合入基线)

**目的**：确认 PR #9（fix: prevent login aliases and password races; stabilize pagination）合并后无回归、文档与代码一致。

**环境**
- Commit: `e803c145adb670f71e71be4196ac403d7edc9e17` (main)
- Docker Desktop + `compose.yaml`（默认无 TLS）
- `python scripts/configure_demo.py` 生成 `.env`
- `docker compose up --build -d --wait`

**自动验证**
- 单元测试：40 ✅
- MySQL 集成测试：22 ✅
- HTTP 集成（带数据库 + 应用重启）：124 ✅
- 行覆盖：86.51%
- Spotless / Enforcer / SpotBugs / FindSecBugs：0 high

**手动复核**
- 限流：60/min IP、5/20/min 账号操作、10 次失败 → 15 min 锁定；进程内桶表满时驱逐最久未用桶（不包括被锁定的桶）。
- CSRF：登录 GET 拿到 token、登录 POST 提交 token；GET 越权直接 401。
- 弱口令：31 条 curated 词表挡住 `123456` 等；同密码改密直接拒绝。
- 并发改密：两个线程同时改密，第二个拿不到 PESSIMISTIC_WRITE 时抛 `StaleObjectStateException`，HTTP 409。
- Unicode 别名登录：零宽字符 / 全角数字 / 变体选择符 / 尾随 NUL 全部归一化失败。
- 跨页排序 tied 行不重不漏：写搜索 / 翻页有二级 sort key `s.sno` 兜底。

**已知未做项**
- Unicode 别名（零宽 / 全角）登录绕过风险：未做。
- 跨页排序 tied 行不重不漏：未做。
- 密码哈希升级时是否会踢掉同账号的其他会话：未做。
- 审计记录与业务事务原子性：未做。

**结论**
- PR #9 解决了上方 4 项已知未做项，本窗口建立的基线可作为后续 dependabot 升级的对照。

---

## 验收窗口 2026-09-20 (PR #9 之前最后基线)

**目的**：建立 PR #9 之前的稳定基线。

**环境**
- Commit: `e803c14` 之前的 stable main
- Docker Desktop + `compose.yaml`（无 TLS）
- `python scripts/configure_demo.py` → `docker compose up --build -d --wait`

**自动验证**
- 单元测试：34 ✅
- MySQL 集成测试：20 ✅
- HTTP 集成：112 ✅
- 行覆盖：86.10%
- Spotless / Enforcer / SpotBugs：0 high

**手动复核**
- 限流：60/min IP、5/20/min 账号操作、10 次失败 → 15 min 锁定。
- CSRF：登录 GET 拿到 token、登录 POST 提交 token；GET 越权直接 401。
- 弱口令：31 条 curated 词表挡住 `123456` 等；同密码改密直接拒绝。
- 并发改密：两个线程同时改密，第二个拿不到 PESSIMISTIC_WRITE 时抛 `StaleObjectStateException`，HTTP 409。

**已知未做项**
- Unicode 别名（零宽 / 全角）登录绕过风险：未做。
- 跨页排序 tied 行不重不漏：未做。
- 密码哈希升级时是否会踢掉同账号的其他会话：未做。
- 审计记录与业务事务原子性：未做。

**结论**
- 上方 4 项已知未做项是 PR #9 的目标。本窗口建立的基线可以与 PR #9 合并后的结果做差分。