"""HTTP acceptance checks against a running local demo. Creates and removes its own test records.
Usage: python scripts/smoke_test.py [--restart]
Uses only Python's standard library. Reads .env without printing credentials.
"""
from pathlib import Path
from urllib.request import Request, build_opener, HTTPCookieProcessor, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
import csv
import io
from http.cookiejar import CookieJar
from html.parser import HTMLParser
import argparse
import json
import os
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
config = {}
env_file = ROOT / ".env"
if env_file.exists():
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            config[key] = value
BASE = os.environ.get("TEST_BASE_URL", "http://localhost:" + config.get("APP_PORT", "8080") + "/studentManagerSix")
ADMIN_PASSWORD = os.environ.get("DEMO_ADMIN_PASSWORD", config.get("DEMO_ADMIN_PASSWORD", ""))
checks = []


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Inputs(HTMLParser):
    def __init__(self, body):
        super().__init__()
        self.values = {}
        self.feed(body)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "input" and attrs.get("name"):
            self.values[attrs["name"]] = attrs.get("value", "")


class Client:
    def __init__(self):
        self.cookies = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies), NoRedirect())

    def request(self, route, data=None, method=None):
        payload = None if data is None else urlencode(data).encode("utf-8")
        req = Request(BASE + route, data=payload, method=method)
        try:
            response = self.opener.open(req, timeout=20)
        except HTTPError as e:
            response = e
        return response.status, response.headers, response.read().decode("utf-8")

    def csrf(self, route):
        status, headers, body = self.request(route)
        require(status == 200, "Form renders: " + route)
        return Inputs(body).values["csrf"]

    def submit(self, route, data, form=None):
        return self.request(route, {"csrf": self.csrf(form or route), **data})

    def login(self, username, password):
        token = self.csrf("/login")
        old_sid = next(c.value for c in self.cookies if c.name == "JSESSIONID")
        result = self.request("/login", {"csrf": token, "username": username, "password": password})
        require(result[0] == 302, "Successful login")
        new_sid = next(c.value for c in self.cookies if c.name == "JSESSIONID")
        require(old_sid != new_sid, "Session ID rotates on login")
        return result


def require(condition, label):
    if not condition:
        raise AssertionError(label)
    checks.append(label)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart", action="store_true", help="Also restart this Compose app/db and verify persistence")
    args = parser.parse_args()
    if not ADMIN_PASSWORD:
        raise RuntimeError("Set DEMO_ADMIN_PASSWORD or create .env before running.")
    public = Client()
    require(public.request("/register")[0] == 410, "Public registration is closed")
    require(public.request("/register", {"sno": "999"})[0] == 410, "POST registration is closed")
    status, headers, body = public.request("/health")
    require(status == 200 and body == "OK", "Database-backed health")
    status, headers, body = public.request("/students")
    require(status == 302 and headers["Location"].endswith("/login"), "Anonymous access redirects")
    public = Client()
    status, headers, body = public.request("/login")
    require(status == 200 and "欢迎回来" in body and "每一份资料" in body, "Login JSP and includes render Chinese")
    cookie = headers.get("Set-Cookie", "")
    require("HttpOnly" in cookie and "SameSite=Lax" in cookie, "Session cookie flags")
    require("frame-ancestors 'none'" in headers.get("Content-Security-Policy", ""), "Content security policy")
    require(public.request("/login", {"username": "admin", "password": "irrelevant"})[0] == 403, "Login CSRF required")
    require(public.submit("/login", {"username": "admin", "password": "wrong-password"})[0] == 401, "Incorrect password rejected")

    admin = Client()
    admin.login("admin", ADMIN_PASSWORD)
    status, _, body = admin.request("/students")
    require(status == 200 and "学生档案" in body, "Admin directory renders")
    require("pbkdf2" not in body and ADMIN_PASSWORD not in body, "No credentials in page")
    require(admin.request("/students/delete?sno=1")[0] == 405, "GET deletion rejected")
    require(admin.request("/students/delete", {"sno": "1"})[0] == 403, "Delete CSRF required")
    require(admin.request("/students?page=abc")[0] == 400, "Malformed page handled")
    require(admin.request("/students?page=-1")[0] == 200, "Negative page clamped")
    require(admin.request("/students?page=999999999999")[0] == 200, "Large page clamped")
    require(admin.request("/students?name=missing-no-student")[0] == 200, "Empty search renders")
    require(admin.request("/students/detail?sno=2147483647")[0] == 404, "Missing record returns 404")
    require(admin.request("/does-not-exist")[0] == 404, "Missing route returns 404")
    require(admin.request("/WEB-INF/views/students.jsp")[0] == 404, "JSP cannot be fetched directly")

    sno = 1_800_000_000 + secrets.randbelow(100_000_000)
    other_sno = sno + 1
    password = "Smoke-only-" + secrets.token_hex(10)
    changed = "Changed-only-" + secrets.token_hex(10)
    name = '验收<script>_%'
    created = []
    try:
        data = {"sno": str(sno), "sname": name, "age": "21", "address": '香港😀<b>"&', "password": password, "role": "ADMIN"}
        # Public registration is closed (P0-1). Admin /students/create covers the same paths.
        status, _, body = admin.submit("/students/create", data)
        require(status == 302, "Admin creates a student")
        created.append(sno)
        require(admin.submit("/students/create", data)[0] == 409, "Duplicate student rejected")
        invalid = {**data, "sno": str(other_sno), "age": "151"}
        require(admin.submit("/students/create", invalid)[0] == 400, "Invalid age rejected")
        require(admin.request("/students/detail?sno=" + str(other_sno))[0] == 404, "Invalid form did not create a row")
        require(admin.submit("/students/create", {**data, "sno": str(other_sno), "sname": "其他学生"})[0] == 302, "Admin creates a second student")
        created.append(other_sno)

        status, _, body = admin.request("/students?name=" + urlencode({"x": "_"}).split("=", 1)[1])
        require(status == 200 and "验收&lt;script&gt;_%" in body and "<script>_%" not in body, "Search results escape HTML")
        require(admin.request("/students/detail?sno=" + str(sno))[0] == 200, "Detail page renders")
        require(admin.submit("/students/edit?sno=" + str(sno), {"sno": str(sno), "sname": "管理员更新", "age": "22", "address": "九龙"})[0] == 302, "Admin edits student")

        # CSV exports precisely the selected page and preserves Chinese text.
        query = urlencode({"size": "10", "page": "2", "sort": "sno", "dir": "asc"})
        status, headers, export = admin.request("/students/export?" + query)
        require(status == 200 and export.startswith("\ufeff学号"), "CSV UTF-8 BOM and header")
        exported = list(csv.reader(io.StringIO(export.lstrip("\ufeff"))))
        require(any(row[0] == str(sno) for row in exported[1:]), "CSV uses requested page")
        require(len(exported) <= 11, "CSV respects selected page size")
        status, _, listing = admin.request("/students?size=10&sort=name&dir=desc")
        require("size=10" in listing and "sort=name" in listing and "dir=desc" in listing, "Pagination retains size and ordering")

        # An invalid reset must retain the target ID and allow a corrected submission.
        reset_client = Client()
        reset_client.login(str(other_sno), password)
        require(reset_client.request("/profile")[0] == 200, "Prewarm student before admin reset")
        reset_path = "/students/reset?sno=" + str(other_sno)
        reset_password = "Reset-only-" + secrets.token_hex(10)
        status, _, error_form = admin.submit(reset_path, {"sno": str(other_sno), "newPassword": reset_password, "confirmPassword": "mismatch"})
        require(status == 400 and Inputs(error_form).values.get("sno") == str(other_sno), "Reset error preserves student ID")
        require(admin.submit(reset_path, {"sno": str(other_sno), "newPassword": reset_password, "confirmPassword": reset_password})[0] == 302, "Admin resets password")
        require(reset_client.request("/profile")[0] == 302, "Admin reset immediately revokes prewarmed session")
        require(reset_client.submit("/login", {"username": str(other_sno), "password": password})[0] == 401, "Reset rejects old password")
        reset_client.login(str(other_sno), reset_password)

        student = Client()
        student.login(str(sno), password)
        second = Client()
        second.login(str(sno), password)
        require(second.request("/profile")[0] == 200, "Prewarm second session before revocation")
        require(student.request("/students")[0] == 403, "Student cannot list all records")
        require(student.request("/students/detail?sno=" + str(other_sno))[0] == 403, "Student cannot read another record")
        require(student.submit("/students/delete", {"sno": str(other_sno)}, form="/profile")[0] == 403, "Student cannot delete a record")
        require(student.submit("/students/edit", {"sno": str(other_sno), "sname": "入侵", "age": "20", "address": ""}, form="/profile")[0] == 403, "Student cannot edit through admin route")
        require(student.request("/profile?sno=" + str(other_sno))[0] == 200, "Profile ignores untrusted student ID")
        require(student.submit("/profile", {"sno": str(other_sno), "sname": "本人更新", "age": "23", "address": "新界😀"})[0] == 302, "Profile updates current identity only")
        require("其他学生" in admin.request("/students/detail?sno=" + str(other_sno))[2], "Other student remains unchanged")
        require("本人更新" in admin.request("/students/detail?sno=" + str(sno))[2], "Current student updated")

        require(student.submit("/password", {"oldPassword": password, "newPassword": changed, "confirmPassword": "mismatch"})[0] == 400, "Password confirmation checked")
        require(student.submit("/password", {"oldPassword": "wrong-password", "newPassword": changed, "confirmPassword": changed})[0] == 400, "Current password checked")
        require(student.submit("/password", {"oldPassword": password, "newPassword": changed, "confirmPassword": changed})[0] == 302, "Password change succeeds")
        require(second.request("/profile")[0] == 302, "Password change invalidates other session")
        require(student.submit("/login", {"username": str(sno), "password": password})[0] == 401, "Old password no longer works")
        student.login(str(sno), changed)

        if args.restart:
            subprocess.run(["docker", "compose", "restart", "db"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
            db_id = subprocess.check_output(["docker", "compose", "ps", "-q", "db"], cwd=ROOT, text=True).strip()
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                health = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Health.Status}}", db_id], text=True).strip()
                if health == "healthy":
                    break
                time.sleep(1)
            else:
                raise AssertionError("Database did not recover after restart")
            subprocess.run(["docker", "compose", "restart", "app"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                try:
                    if Client().request("/health")[0] == 200:
                        break
                except (OSError, URLError):
                    pass
                time.sleep(1)
            else:
                raise AssertionError("Application did not recover after restart")
            admin = Client()
            admin.login("admin", ADMIN_PASSWORD)
            student = Client()
            student.login(str(sno), changed)
            require("本人更新" in student.request("/profile")[2], "Restart preserves profile and changed password")
            require("其他学生" in admin.request("/students/detail?sno=" + str(other_sno))[2], "Restart preserves added records")

        require(admin.submit("/students/delete", {"sno": str(sno)}, form="/students")[0] == 302, "Admin deletes student and account")
        created.remove(sno)
        require(student.request("/profile")[0] == 302, "Deleted account session rejected")
        require(student.submit("/login", {"username": str(sno), "password": changed})[0] == 401, "Deleted account cannot log in")
        require(admin.request("/students/detail?sno=" + str(sno))[0] == 404, "Deleted record is missing")
        require(admin.submit("/logout", {}, form="/students")[0] == 302, "Logout succeeds")
        require(admin.request("/students")[0] == 302, "Logged-out session cannot access directory")
    finally:
        if created:
            cleanup = Client()
            cleanup.login("admin", ADMIN_PASSWORD)
            for number in created:
                result = cleanup.submit("/students/delete", {"sno": str(number)}, form="/students")
                if result[0] != 302:
                    raise AssertionError("Could not clean up smoke-test record")
    # Unique account keeps the rate-limit test independent of application accounts.
    limited = Client()
    unknown = "limit-" + secrets.token_hex(8)
    for _ in range(5):
        require(limited.submit("/login", {"username": unknown, "password": "not-a-real-password"})[0] == 401, "Rate limit allows initial failed attempt")
    status, headers, _ = limited.submit("/login", {"username": unknown.upper(), "password": "not-a-real-password"})
    require(status == 429 and headers.get("Retry-After"), "Rate limit rejects sixth attempt including case variants")
    report = {"passed": len(checks), "restart_checked": args.restart, "checks": checks}
    (ROOT / "target").mkdir(exist_ok=True)
    (ROOT / "target" / "http-acceptance.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"PASS: {len(checks)} HTTP acceptance checks. Report: target/http-acceptance.json")


if __name__ == "__main__":
    main()
