#!/usr/bin/env bash
# Stop the port-forward and delete the local kind cluster.
set -euo pipefail

CLUSTER="${KIND_CLUSTER_NAME:-spend-recon}"
NAMESPACE="spend-recon"
HOST_PORT="${KIND_HOST_PORT:-8080}"
PF_PID_FILE="/tmp/spend-recon-kind-${CLUSTER}.port-forward.pid"

if ! command -v kind >/dev/null 2>&1; then
  echo "Missing kind." >&2
  exit 1
fi

if [[ -f "$PF_PID_FILE" ]]; then
  pid="$(tr -d '[:space:]' <"$PF_PID_FILE")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    if [[ "$cmd" == *port-forward* ]]; then
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

# A forward started by hand, with the same namespace and port, should stop too.
if command -v pkill >/dev/null 2>&1; then
  pkill -f "kubectl -n ${NAMESPACE} port-forward --address 127.0.0.1 svc/spend-recon ${HOST_PORT}:8080" 2>/dev/null || true
fi

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "Deleting kind cluster $CLUSTER"
  kind delete cluster --name "$CLUSTER"
else
  echo "No kind cluster named $CLUSTER."
fi
