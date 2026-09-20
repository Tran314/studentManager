"""Back up and apply missing v2/v3 schema additions without resetting the data volume.

Usage: python scripts/migrate_database.py
Runs against the selected Compose database. Credentials stay inside its environment.
Stop the app before upgrading an existing deployment; restart it after this succeeds.
"""
from pathlib import Path
import argparse
import hashlib
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", default="compose.yaml")
    parser.add_argument("--project")
    parser.add_argument("--service", default="db")
    args = parser.parse_args()
    base = ["docker", "compose", "-f", args.compose_file]
    if args.project:
        base += ["-p", args.project]
    base += ["exec", "-T", args.service, "sh", "-c"]
    client = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names'

    def sql(statement):
        result = subprocess.run(base + [client], input=statement.encode("utf-8"),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT, check=True)
        return result.stdout.decode("utf-8").strip()

    # Refuse an uninitialised/wrong database before any write.
    if sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('student','account','app_seed');") != "3":
        raise RuntimeError("Expected the existing student/account/app_seed schema; nothing changed.")
    missing_index = sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='student' AND index_name='idx_student_sname';") == "0"
    missing_audit = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='audit_log';") == "0"
    if not missing_index and not missing_audit:
        print("Schema v3 already present; no changes needed.")
        return

    backup_dir = ROOT / "work" / "backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / (datetime.now(timezone.utc).strftime("schema-upgrade-%Y%m%dT%H%M%S%fZ.sql"))
    dump = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysqldump -u "$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"'
    with backup.open("xb") as stream:
        subprocess.run(base + [dump], stdout=stream, stderr=subprocess.PIPE, cwd=ROOT, check=True)
    if backup.stat().st_size == 0:
        raise RuntimeError("Empty backup; migration aborted.")
    for needed, filename in [(missing_index, "002-index.sql"), (missing_audit, "003-audit.sql")]:
        if needed:
            content = (ROOT / "docker" / "init" / filename).read_text(encoding="utf-8")
            sql(content)
            print("Applied", filename, "sha256=" + hashlib.sha256(content.encode()).hexdigest())
    # Verify both additions before reporting success. An interrupted run can be safely retried.
    if sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='student' AND index_name='idx_student_sname';") != "1":
        raise RuntimeError("Index verification failed; retain the backup and inspect the schema.")
    sql("SELECT actor_username, actor_role, action, target_sno, target_username, details, created_at FROM audit_log LIMIT 0;")
    print("Schema v3 verified. Backup:", backup)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # Do not print command/environment/output that may include database contents.
        raise SystemExit(f"Database command failed (exit {error.returncode}); no automatic rollback was attempted. Retain work/backups and inspect the database logs.") from None
