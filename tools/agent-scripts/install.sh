#!/bin/bash
# Installs the Glance Kit agent scripts to ~/.glancekit/bin (outside any project).
# Run once on your Mac:  bash tools/agent-scripts/install.sh
set -euo pipefail
[ "$(uname)" = "Darwin" ] || { echo "These scripts use the macOS Keychain; run this on your Mac."; exit 1; }
for t in curl python3 git; do command -v "$t" >/dev/null || { echo "Missing $t. Install the Xcode Command Line Tools: xcode-select --install"; exit 1; }; done
src="$(cd "$(dirname "$0")/bin" && pwd)"
dest="$HOME/.glancekit/bin"
mkdir -p "$dest"
chmod u+w "$dest" "$dest"/* 2>/dev/null || true
cp "$src"/gk-* "$dest"/
chmod 555 "$dest"/gk-*
chmod 555 "$dest"
chmod 700 "$HOME/.glancekit"
echo "Installed to $dest:"
ls "$dest"
echo
if /usr/bin/security find-generic-password -a "$USER" -s glancekit.github-token >/dev/null 2>&1; then
  echo "GitHub token: already in your Keychain."
else
  echo "Next: store your GitHub token in the Keychain (it won't be shown):"
  echo "  ~/.glancekit/bin/gk-setup github-token"
fi
echo "Then check it works:  ~/.glancekit/bin/gk-status latest build.yml"
