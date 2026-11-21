#!/usr/bin/env bash
# Stop the port-forward and delete the local kind cluster this repo created.
# A cluster without the kind-up.sh node label is left in place.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=kind-lib.sh
source "$ROOT/scripts/kind-lib.sh"

if ! command -v kind >/dev/null 2>&1; then
  echo "Missing kind." >&2
  exit 1
fi
if ! command -v kubectl >/dev/null 2>&1; then
  echo "Missing kubectl." >&2
  exit 1
fi

CLUSTER="$(kind_cluster_name)"
PF_PID_FILE="$(kind_pf_pid_file)"

stop_our_port_forward() {
  local pid cmd
  if [[ -f "$PF_PID_FILE" ]]; then
    pid="$(tr -d '[:space:]' <"$PF_PID_FILE")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
      if [[ "$cmd" == *port-forward* && "$cmd" == *"$(kind_context_name)"* ]]; then
        kill "$pid" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do
          kill -0 "$pid" 2>/dev/null || break
          sleep 0.2
        done
        kill -9 "$pid" 2>/dev/null || true
      fi
    fi
    rm -f "$PF_PID_FILE"
  fi

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$(kind_port_forward_pattern)" 2>/dev/null || true
  fi
}

ownership="$(kind_ownership)"
case "$ownership" in
  missing)
    stop_our_port_forward
    echo "No kind cluster named ${CLUSTER}."
    ;;
  owned)
    stop_our_port_forward
    echo "Deleting kind cluster ${CLUSTER}"
    kind delete cluster --name "$CLUSTER"
    ;;
  foreign)
    kind_refuse_foreign delete
    exit 1
    ;;
  *)
    echo "Unexpected cluster ownership result: ${ownership}" >&2
    exit 1
    ;;
esac
