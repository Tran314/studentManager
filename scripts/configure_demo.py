"""Create local demo secrets without printing them or overwriting existing configuration."""
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / ".env"
values = {key: secrets.token_urlsafe(24) for key in (
    "DB_PASSWORD", "DB_ROOT_PASSWORD", "DEMO_ADMIN_PASSWORD", "DEMO_STUDENT_PASSWORD")}
with path.open("x", encoding="utf-8", newline="\n") as stream:
    stream.write("# Local configuration; never commit this file.\n")
    for key, value in values.items():
        stream.write(f"{key}={value}\n")
    stream.write("APP_PORT=8080\n")
print("Created .env with random passwords. Existing configurations are never overwritten.")
