#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cat > "$fixture/gradle" <<'WRAPPER'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$SOCIAL_GATE_LOG"
if [[ "$1" == "$SOCIAL_FAIL_TASK" ]]; then exit 7; fi
WRAPPER
chmod +x "$fixture/gradle"
export SOCIAL_GRADLE_WRAPPER="$fixture/gradle" SOCIAL_GATE_LOG="$fixture/log" SOCIAL_FAIL_TASK=detekt
if "$root/scripts/verify.sh"; then echo 'quality failure was ignored' >&2; exit 1; fi
[[ "$(cat "$fixture/log")" == detekt ]]
: > "$fixture/log"
export SOCIAL_FAIL_TASK=jvmTest
if "$root/scripts/verify.sh"; then echo 'test failure was ignored' >&2; exit 1; fi
[[ "$(wc -l < "$fixture/log" | tr -d ' ')" == 2 ]]
: > "$fixture/log"
export SOCIAL_FAIL_TASK=none
"$root/scripts/verify.sh"
[[ "$(wc -l < "$fixture/log" | tr -d ' ')" == 3 ]]
