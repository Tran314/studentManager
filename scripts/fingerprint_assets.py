"""Refresh asset filenames after editing CSS/JS; update all JSP references."""
from pathlib import Path
import hashlib
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/webapp/assets"


def main():
    for asset in sorted(ASSETS.iterdir()):
        match = re.fullmatch(r"(.+)\.[0-9a-f]{8}\.(css|js|svg)", asset.name)
        if not match:
            continue
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()[:8]
        name = f"{match[1]}.{digest}.{match[2]}"
        if name == asset.name:
            continue
        for view in (ROOT / "src/main/webapp/WEB-INF/views").iterdir():
            if view.suffix in {".jsp", ".jspf"}:
                text = view.read_text(encoding="utf-8")
                view.write_text(text.replace(asset.name, name), encoding="utf-8", newline="\n")
        asset.rename(asset.with_name(name))
        print(asset.name, "->", name)


if __name__ == "__main__":
    main()
