#!/usr/bin/env bash
# End-to-end check over stdio: initialize, list tools, call every tool against the test fixture vaults.
# Usage: scripts/smoke-test.sh [command...]   (default: the native binary in target/)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -gt 0 ]]; then CMD=("$@"); else CMD=(target/obsidian-mcp-1.0.0-SNAPSHOT-runner); fi

WORK=$(mktemp -d)
trap '[[ -n "${KEEP:-}" ]] || rm -rf "$WORK"' EXIT
echo "work dir: $WORK" >&2
FIX="$PWD/src/test/resources/vaults"
cat > "$WORK/config.yaml" <<YAML
vaults:
  - name: work
    path: $FIX/work
    allowed-tags: [project, howto]
    ignored-tags: [private]
  - name: personal
    path: $FIX/personal
    ignored-tags: [journal]
    show-untagged: true
YAML

requests() {
  cat <<'JSON'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_vaults","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_notes","arguments":{"tag":"project"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_note","arguments":{"id":"work/Projects/Alpha.md"}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"search_notes","arguments":{"query":"cinema"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_note","arguments":{"id":"work/Secret.md"}}}
JSON
  sleep "${SMOKE_WAIT:-2}"
}

START=$(date +%s%N 2>/dev/null || python3 -c 'import time;print(time.time_ns())')
requests | OBSIDIAN_MCP_CONFIG="$WORK/config.yaml" "${CMD[@]}" > "$WORK/out.jsonl" 2> "$WORK/err.log" || true

fail() { echo "FAIL: $*"; echo "--- stdout"; cat "$WORK/out.jsonl"; echo "--- stderr"; cat "$WORK/err.log"; exit 1; }
expect() { grep -q -- "$1" "$WORK/out.jsonl" || fail "expected output to contain: $1"; }
reject() { ! grep -q -- "$1" "$WORK/out.jsonl" || fail "output must not contain: $1"; }

[[ $(wc -l < "$WORK/out.jsonl") -ge 7 ]] || fail "expected 7 responses"
expect '"name":"list_notes"'
expect '"name":"get_note"'
expect '"name":"search_notes"'
expect '"name":"list_vaults"'
expect 'work/Projects/Beta.md'
expect '\[image: diagram.png\]'
expect 'https://quarkus.io/guides/'
expect 'personal/Movies.md'
expect 'Note not found: work/Secret.md'
reject 'Top secret'
reject 'leak.example.com'
reject 'private/finance'

if command -v jq >/dev/null; then
  echo "--- get_note(work/Projects/Alpha.md)"
  jq -r 'select(.id==5) | .result.content[0].text | fromjson | .content' "$WORK/out.jsonl"
fi
echo "OK: all smoke checks passed"
