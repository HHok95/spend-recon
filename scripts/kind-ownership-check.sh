#!/usr/bin/env bash
# Prove kind-up.sh and kind-down.sh leave a cluster alone unless kind-up.sh marked it.
# Uses stub kind, kubectl, and docker binaries prepended to PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BIN="$WORK/bin"
LOG="$WORK/kind.log"
KUBECONFIG_FILE="$WORK/kubeconfig"
mkdir -p "$BIN"
: >"$LOG"

cat >"$BIN/kind" <<'EOF'
#!/usr/bin/env bash
printf 'KIND %s\n' "$*" >>"$KIND_STUB_LOG"
cmd="$1"
shift || true
case "$cmd" in
  version)
    printf '%s\n' "${KIND_STUB_VERSION:-kind v0.33.0 go1.26.7 linux/amd64}"
    ;;
  get)
    sub="${1:-}"
    if [[ "$sub" == "clusters" ]]; then
      if [[ -n "${KIND_STUB_CLUSTERS:-}" ]]; then
        printf '%s\n' "$KIND_STUB_CLUSTERS"
      fi
    elif [[ "$sub" == "kubeconfig" ]]; then
      printf '%s\n' "apiVersion: v1
kind: Config
current-context: kind-stub
"
    fi
    ;;
  delete)
    printf 'DELETE %s\n' "$*" >>"$KIND_STUB_LOG"
    ;;
  create)
    printf 'CREATE %s\n' "$*" >>"$KIND_STUB_LOG"
    ;;
  load)
    printf 'LOAD %s\n' "$*" >>"$KIND_STUB_LOG"
    ;;
  *)
    echo "stub kind: unexpected ${cmd} $*" >&2
    exit 1
    ;;
esac
EOF

cat >"$BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
printf 'KUBECTL %s\n' "$*" >>"$KIND_STUB_LOG"
joined=" $* "
if [[ "$joined" == *" use-context "* ]]; then
  echo "kubectl config use-context is not allowed" >&2
  exit 97
fi
context="kind-${SPEND_RECON_KIND_CLUSTER:?}"
if [[ "$joined" != *" --context ${context} "* && "$joined" != *" --context ${context}" ]]; then
  echo "missing --context ${context}" >&2
  echo "args: $*" >&2
  exit 98
fi
if [[ "$joined" != *" --kubeconfig "* ]]; then
  echo "missing --kubeconfig" >&2
  exit 99
fi
if [[ "$joined" == *" get nodes "* || "$joined" == *" get node "* ]]; then
  if [[ "${KIND_STUB_NODES_FAIL:-0}" == "1" ]]; then
    echo "nodes unreadable" >&2
    exit 1
  fi
  if [[ "${KIND_STUB_MARKER:-}" == "kind-up.sh" ]]; then
    printf '%s\n' "kind-up.sh"
  fi
  exit 0
fi
exit 0
EOF

cat >"$BIN/docker" <<'EOF'
#!/usr/bin/env bash
printf 'DOCKER %s\n' "$*" >>"$KIND_STUB_LOG"
if [[ "${1:-}" == "build" ]]; then
  echo "docker build ran and this check did not expect it" >&2
  exit 1
fi
exit 0
EOF

chmod +x "$BIN/kind" "$BIN/kubectl" "$BIN/docker"

export PATH="$BIN:$PATH"
export KIND_STUB_LOG="$LOG"
export SPEND_RECON_KUBECONFIG="$KUBECONFIG_FILE"
export KIND_CLUSTER_NAME="other-project"
export SPEND_RECON_KIND_CLUSTER="foreign-cluster"
export KIND_STUB_VERSION="kind v0.33.0 go1.26.7 linux/amd64"
export KIND_STUB_CLUSTERS="foreign-cluster"
export KIND_STUB_MARKER=""
export KIND_STUB_NODES_FAIL=0

fail() {
  echo "FAIL: $*" >&2
  echo "--- stub log ---" >&2
  cat "$LOG" >&2 || true
  exit 1
}

reset_log() {
  : >"$LOG"
}

assert_absent() {
  local needle="$1"
  if grep -F -e "$needle" "$LOG" >/dev/null; then
    fail "log contained unexpected: ${needle}"
  fi
}

assert_present() {
  local needle="$1"
  if ! grep -F -e "$needle" "$LOG" >/dev/null; then
    fail "log missing: ${needle}"
  fi
}

run_expect_fail() {
  local label="$1"
  shift
  local out rc
  set +e
  out="$("$@" 2>&1)"
  rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    printf '%s\n' "$out" >&2
    fail "${label} exited 0"
  fi
  printf '%s\n' "$out"
}

reset_log
out="$(run_expect_fail "kind-down unmarked" "$ROOT/scripts/kind-down.sh")"
printf '%s\n' "$out" | grep -q "Refusing to delete" || fail "kind-down did not say it was refusing to delete"
assert_absent "DELETE"
assert_absent "other-project"
assert_present "--context kind-foreign-cluster"
echo "PASS unmarked cluster is not deleted"

reset_log
out="$(run_expect_fail "kind-up unmarked" "$ROOT/scripts/kind-up.sh")"
printf '%s\n' "$out" | grep -q "Refusing to reuse" || fail "kind-up did not say it was refusing to reuse"
assert_absent "CREATE"
assert_absent "DELETE"
assert_absent "DOCKER build"
assert_absent "other-project"
assert_present "--context kind-foreign-cluster"
echo "PASS unmarked cluster is not reused"

reset_log
export KIND_STUB_MARKER="kind-up.sh"
"$ROOT/scripts/kind-down.sh" >/dev/null
assert_present "DELETE cluster --name foreign-cluster"
assert_absent "other-project"
assert_absent "CREATE"
echo "PASS marked cluster is deleted by its own name"

reset_log
export KIND_STUB_MARKER=""
export KIND_STUB_NODES_FAIL=1
out="$(run_expect_fail "kind-down unreadable" "$ROOT/scripts/kind-down.sh")"
printf '%s\n' "$out" | grep -q "could not be read" || fail "kind-down did not explain the unreadable nodes"
assert_absent "DELETE"
echo "PASS unreadable cluster is not deleted"

reset_log
export KIND_STUB_NODES_FAIL=0
export KIND_STUB_CLUSTERS=""
export KIND_STUB_VERSION="kind v0.23.0 go1.21.0 linux/amd64"
out="$(run_expect_fail "kind-up old" "$ROOT/scripts/kind-up.sh")"
printf '%s\n' "$out" | grep -q "0.24.0" || fail "kind-up did not name the minimum version"
assert_absent "CREATE"
assert_absent "DELETE"
assert_absent "DOCKER build"
echo "PASS kind 0.23.0 is rejected"

reset_log
export KIND_STUB_VERSION="kind v0.24.0 go1.22.0 linux/amd64"
export KIND_STUB_CLUSTERS="foreign-cluster"
out="$(run_expect_fail "kind-up 0.24 foreign" "$ROOT/scripts/kind-up.sh")"
printf '%s\n' "$out" | grep -q "Refusing to reuse" || fail "kind 0.24.0 did not reach the ownership check"
if printf '%s\n' "$out" | grep -q "older than"; then
  fail "kind 0.24.0 was treated as too old"
fi
assert_absent "CREATE"
echo "PASS kind 0.24.0 is accepted and still refuses a foreign cluster"

if grep -n "use-context" \
  "$ROOT/scripts/kind-up.sh" \
  "$ROOT/scripts/kind-down.sh" \
  "$ROOT/scripts/kind-lib.sh" \
  "$ROOT/.cursor/skills/verify-spend-recon/scripts/spend-recon-verify"; then
  fail "use-context is still present"
fi
echo "PASS no kubectl config use-context"

if grep -n "KIND_CLUSTER_NAME" \
  "$ROOT/scripts/kind-up.sh" \
  "$ROOT/scripts/kind-down.sh" \
  "$ROOT/scripts/kind-lib.sh" \
  "$ROOT/.cursor/skills/verify-spend-recon/scripts/spend-recon-verify"; then
  fail "KIND_CLUSTER_NAME is still read"
fi
echo "PASS kind's cluster-name setting is not read"

if ! grep -q "kind_kubectl" "$ROOT/.cursor/skills/verify-spend-recon/scripts/spend-recon-verify"; then
  fail "drive kind does not pass the cluster context through kind_kubectl"
fi
echo "PASS drive kind uses kind_kubectl"

echo "kind ownership check passed"
