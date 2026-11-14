# Shared helpers for the Glance Kit agent scripts. Sourced, not run.
# Keys come from the macOS Keychain (service "glancekit.<name>") and are never printed.
set -euo pipefail

GK_REPO="Athulsn10/glance-kit"
GK_API="https://api.github.com/repos/$GK_REPO"
GK_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

gk_die() { echo "$*" >&2; exit 1; }

# Read a key from the Keychain. Only ever used inside a pipe or a header, never echoed.
gk_secret() {
  /usr/bin/security find-generic-password -a "$USER" -s "glancekit.$1" -w 2>/dev/null \
    || gk_die "Key \"glancekit.$1\" isn't in your Keychain yet. Run: ~/.glancekit/bin/gk-setup $1"
}

# Hide anything that looks like a key in output.
gk_mask() {
  sed -E \
    -e 's/(ghp_|gho_|ghu_|ghs_|ghr_|github_pat_)[A-Za-z0-9_]{16,}/[hidden]/g' \
    -e 's/-----BEGIN [A-Z ]*PRIVATE KEY-----.*/[hidden private key]/g' \
    -e 's/("private_key"[[:space:]]*:[[:space:]]*")[^"]*/\1[hidden]/g' \
    -e 's/([Aa]uthorization:)[^,}]*/\1 [hidden]/g'
}

# GitHub API call. The token goes to curl on stdin, so it never appears in the process list.
# Usage: gk_api METHOD PATH [JSON]
gk_api() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-sS -X "$method" -H @- -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
  [ -n "$data" ] && args+=(-H "Content-Type: application/json" -d "$data")
  printf 'Authorization: Bearer %s\n' "$(gk_secret github-token)" | curl "${args[@]}" "$GK_API$path"
}

gk_json() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }

# Start a workflow on main and print its run id once GitHub shows it.
# Usage: gk_dispatch WORKFLOW_FILE ['{"key":"value"}']
gk_dispatch() {
  local wf="$1" inputs="${2:-}" last resp id="" i
  [ -n "$inputs" ] || inputs='{}'
  last="$(gk_api GET "/actions/workflows/$wf/runs?per_page=1" | gk_json "r=d.get('workflow_runs',[]); print(r[0]['id'] if r else 0)")"
  resp="$(gk_api POST "/actions/workflows/$wf/dispatches" "{\"ref\":\"main\",\"inputs\":$inputs}")"
  if [ -n "$resp" ]; then
    echo "$resp" | gk_mask | gk_json "print('GitHub refused to start the workflow:', d.get('message', d))" >&2 || echo "$resp" | gk_mask >&2
    exit 1
  fi
  for i in $(seq 1 15); do
    sleep 4
    id="$(gk_api GET "/actions/workflows/$wf/runs?per_page=3" | gk_json "r=[x['id'] for x in d.get('workflow_runs',[]) if x['id']>$last]; print(min(r) if r else '')")"
    [ -n "$id" ] && break
  done
  [ -n "$id" ] || gk_die "GitHub accepted the request but no run of $wf appeared yet. Check: ~/.glancekit/bin/gk-status latest $wf"
  echo "$id"
}

# Print a run's status and, when finished, its annotations (errors, notices).
gk_report() {
  local id="$1"
  gk_api GET "/actions/runs/$id" | gk_json "print(f\"run {d['id']} · {d['name']} · {d['status']} · {d.get('conclusion') or '-'} · {d['html_url']}\")"
  local status
  status="$(gk_api GET "/actions/runs/$id" | gk_json "print(d['status'])")"
  [ "$status" = "completed" ] || return 0
  local jobs j
  jobs="$(gk_api GET "/actions/runs/$id/jobs" | gk_json "print(' '.join(str(j['id']) for j in d.get('jobs',[])))")"
  for j in $jobs; do
    gk_api GET "/check-runs/$j/annotations" | gk_json "
skip=('Node.js','ubuntu-latest label','is deprecated')
for a in sorted(d if isinstance(d,list) else [], key=lambda a: a.get('start_line') or 0):
    m=a.get('message','')
    if any(s in m for s in skip): continue
    print(f\"[{a['annotation_level']}] {a.get('title') or ''}\")
    print(m[:6000])
" | gk_mask
  done
}

# Wait for a run to finish (up to N seconds), then report. Re-run to keep waiting.
gk_wait() {
  local id="$1" limit="${2:-100}" waited=0 status
  while :; do
    status="$(gk_api GET "/actions/runs/$id" | gk_json "print(d['status'])")"
    [ "$status" = "completed" ] && break
    [ "$waited" -ge "$limit" ] && { echo "Still running after ${waited}s. Run again: ~/.glancekit/bin/gk-status $id --wait"; break; }
    sleep 10; waited=$((waited + 10))
  done
  gk_report "$id"
}
