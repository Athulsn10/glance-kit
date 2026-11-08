"""Print the CHANGELOG.md section for one version (used as the GitHub release notes)."""
import re, sys

version = sys.argv[1]
text = open("CHANGELOG.md", encoding="utf-8").read()
m = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.S | re.M)
if not m:
    sys.exit(f"CHANGELOG.md has no section for {version}. Add one before releasing.")
print(m.group(1).strip())
