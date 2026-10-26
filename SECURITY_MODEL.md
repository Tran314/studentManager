# Security model — 学生管理 SIX (Tran314/studentManager)

_Threat model built from the code and in-repo infrastructure (Dockerfile, compose*.yaml, docker/Caddyfile, docker/tomcat-server.xml). Security scans use it to prioritise findings. Items marked **(assumption)** have not been confirmed by the owner yet; update them and the open questions as they are answered._

## System overview
Server-rendered Java web app: JSP → `StudentServlet` → `StudentServiceImpl` → Hibernate DAOs → MySQL 9.7, running in Tomcat 11 (Docker, uid 10001). Optional Caddy reverse proxy terminates TLS (`compose.tls.yaml`). README positions it as a local teaching / portfolio / small internal admin tool, bound to `127.0.0.1` by default **(assumption: this is the real deployment)**.

## Components
| Name | Covers | Notes |
|---|---|---|
| **web-security-filter** | `src/main/java/com/utils/WebSecurityFilter.java`, `RateLimiter.java` | Security headers/CSP, lazy session, per-request identity revalidation (`auth_version`), role gate (`/students*` admin, `/profile` student), CSRF on every POST, IP+account rate limiting |
| **web-app** | `src/main/java/com/servlets/*`, `src/main/webapp/**` (JSP views, assets) | Routing, parameter parsing/validation (`utils/Validation`, `Limits`), CSV export, JSP output via `<c:out>` |
| **auth** | `service/StudentServiceImpl` login/changePassword/resetPassword/current, `utils/Passwords.java`, `pojo/Account`, `pojo/Identity` | PBKDF2-SHA256, per-account `auth_version` session revocation, row locking on password changes |
| **data-access** | `src/main/java/com/dao/*`, `pojo/*`, `service/AuditServiceImpl`, `utils/HibernateSessionFactoryUtil`, `docker/init/*.sql` | HQL with bound params; dynamic `ORDER BY` from whitelisted `sortColumn`; LIKE with `!` escape; audit_log in same transaction |
| **infrastructure** | `Dockerfile`, `compose*.yaml`, `docker/Caddyfile`, `docker/tomcat-server.xml`, `.github/workflows/*` | Tomcat port 8080 on 127.0.0.1; MySQL not exposed to host; DB link `sslMode=DISABLED` inside Compose network; Caddy on 127.0.0.1:443 |
| **ops-scripts** | `scripts/*.py` | Local `.env` generation, DB migration via `docker compose exec`, smoke tests, asset fingerprinting |

## Trust boundaries
1. Browser ↔ Tomcat (HTTP, or HTTPS via Caddy). Everything in requests is untrusted.
2. Anonymous ↔ authenticated (session cookie + `identity` session attribute, revalidated per request).
3. Student ↔ admin role (enforced in `WebSecurityFilter` path checks + servlet uses `identity.studentSno()` for `/profile`).
4. App ↔ MySQL (Compose-internal network, app DB user `student_app`).
5. Host/operator ↔ containers (`.env` secrets, Docker, CI).

## Entry points
| Route | Who |
|---|---|
| `/login` GET/POST, `/`, `/health`, `/assets/*`, `/register` (410) | Anyone who can reach the port |
| `/logout`, `/password` | Any logged-in user |
| `/profile` GET/POST | Students (own record only) |
| `/students`, `/students/export`, `/detail`, `/create`, `/edit`, `/reset`, `/delete` | Admin only |
| `HealthProbe` (container healthcheck), `scripts/*.py` | Operator on the host |

## Assets
- Student PII: name, age, address (`student` table).
- Credentials: PBKDF2 password hashes, session cookies, CSRF tokens, `.env` secrets (DB/root/demo passwords).
- Admin capability: create/edit/delete students, reset passwords.
- Audit log integrity (`audit_log`).
- Availability (single-process app; PBKDF2 concurrency capped at 4).

## Attacker profiles (assumptions until confirmed)
- **Unauthenticated network user** who can reach the app port — the main external attacker if ever exposed beyond localhost.
- **Authenticated student** trying to read/modify other students' data or reach admin functions (horizontal/vertical privilege escalation).
- **Cross-site attacker** luring a logged-in admin's browser (CSRF, XSS, clickjacking).
- Malicious admin and host/CI-level attackers are considered lower priority **(assumption)**: admin is fully trusted, host access already implies full compromise.

## Existing controls
CSRF token on all POSTs (constant-time compare); session ID rotation on login; 30-min idle timeout; cookie Secure flag when `COOKIE_SECURE=true`; CSP `default-src 'self'`, no inline script, `frame-ancestors 'none'`, X-Frame-Options DENY; `<c:out>` escaping; parameterized HQL; CSV formula-prefix guard; rate limits (60/min/IP sensitive, 5/min/account login & password, lockout after 10 failures/15 min); public registration disabled; non-root container, `no-new-privileges`, DB not published to host; SpotBugs+FindSecBugs and OWASP dependency-check in CI.

## Out of scope / accepted risk
- Documented in README (not yet confirmed as accepted by owner): rate limiter is per-process in-memory and resets on restart; clients behind Caddy share one IP quota; `X-Forwarded-For` deliberately not trusted.
- Nothing else confirmed yet.

## Open questions
1. Is the app ever exposed beyond localhost (LAN / internet via the TLS overlay)?
2. Which attackers matter — unauthenticated users, students, malicious admins, host/CI access?
3. Which assets matter most — student PII, credentials, audit log, availability?
4. Are `scripts/`, CI workflows, Compose-internal plaintext DB traffic, and demo seed accounts out of scope / accepted?
