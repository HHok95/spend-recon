#!/usr/bin/env bash
# Start a local kind cluster, load the app image, and forward the API to localhost.
# Requires kind 0.24.0 or newer. See scripts/kind-lib.sh.
# Local only. This does not deploy to a shared cluster.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=kind-lib.sh
source "$ROOT/scripts/kind-lib.sh"

IMAGE="${KIND_APP_IMAGE:-spend-recon:local}"
HOST_PORT="${KIND_HOST_PORT:-8080}"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing $1. Install it, then run this script again." >&2
    exit 1
  fi
}

need docker
need kind
need kubectl
need curl

kind_require_version

CLUSTER="$(kind_cluster_name)"
PF_PID_FILE="$(kind_pf_pid_file)"
PF_LOG="$(kind_pf_log_file)"
KUBECONFIG_FILE="$(kind_kubeconfig_file)"

# Refuse a cluster this script did not create before building the image.
ownership="$(kind_ownership)"
case "$ownership" in
  missing)
    creating=1
    ;;
  owned)
    creating=0
    echo "Reusing kind cluster ${CLUSTER}"
    echo "kube-proxy mode comes from the existing cluster. make kind-down recreates it from scripts/kind-config.yaml."
    ;;
  foreign)
    kind_refuse_foreign reuse
    exit 1
    ;;
  *)
    echo "Unexpected cluster ownership result: ${ownership}" >&2
    exit 1
    ;;
esac

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running." >&2
  exit 1
fi

if (echo >/dev/tcp/127.0.0.1/"$HOST_PORT") >/dev/null 2>&1; then
  echo "127.0.0.1:${HOST_PORT} is already open. Stop the other process, or set KIND_HOST_PORT." >&2
  exit 1
fi

echo "Building $IMAGE"
docker build -t "$IMAGE" "$ROOT"

if [[ "$creating" -eq 1 ]]; then
  echo "Creating kind cluster ${CLUSTER}"
  mkdir -p "$(dirname "$KUBECONFIG_FILE")"
  # --kubeconfig keeps this cluster out of the default kubeconfig and does not
  # change the current context.
  kind create cluster \
    --name "$CLUSTER" \
    --kubeconfig "$KUBECONFIG_FILE" \
    --config "$ROOT/scripts/kind-config.yaml" \
    --wait 180s
  chmod 600 "$KUBECONFIG_FILE" || true
  if ! kind_mark_nodes; then
    echo "Created ${CLUSTER} but could not label its nodes ${KIND_MARKER_KEY}=${KIND_MARKER_VALUE}." >&2
    echo "Deleting this new cluster so it is not left unmarked." >&2
    kind delete cluster --name "$CLUSTER" --kubeconfig "$(kind_kubeconfig_file)"
    exit 1
  fi
fi

echo "Loading $IMAGE into $CLUSTER"
kind load docker-image "$IMAGE" --name "$CLUSTER"
# postgres:16-alpine is a public image. The node pulls it.
# kind load of that multi-arch image fails on Docker's containerd image store.

echo "Applying manifests"
kind_kubectl apply -k "$ROOT/k8s"

echo "Waiting for Postgres"
kind_kubectl -n "$SPEND_RECON_NAMESPACE" rollout status deployment/postgres --timeout=180s
echo "Waiting for the app"
if ! kind_kubectl -n "$SPEND_RECON_NAMESPACE" rollout status deployment/spend-recon --timeout=300s; then
  kind_kubectl -n "$SPEND_RECON_NAMESPACE" describe deployment/spend-recon >&2 || true
  kind_kubectl -n "$SPEND_RECON_NAMESPACE" logs deployment/spend-recon --tail=120 >&2 || true
  exit 1
fi

# nohup and disown keep the forward alive after this script exits.
# The command line matches kind_port_forward_pattern, which kind-down.sh uses.
nohup kubectl --kubeconfig "$KUBECONFIG_FILE" --context "kind-${CLUSTER}" \
  -n "$SPEND_RECON_NAMESPACE" port-forward --address 127.0.0.1 "svc/spend-recon" "${HOST_PORT}:8080" \
  >"$PF_LOG" 2>&1 &
echo $! >"$PF_PID_FILE"
disown || true

ready=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${HOST_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" -ne 1 ]]; then
  echo "The API did not answer on http://127.0.0.1:${HOST_PORT}/actuator/health" >&2
  echo "Port-forward log: $PF_LOG" >&2
  cat "$PF_LOG" >&2 || true
  if [[ -f "$PF_PID_FILE" ]]; then
    kill "$(tr -d '[:space:]' <"$PF_PID_FILE")" 2>/dev/null || true
    rm -f "$PF_PID_FILE"
  fi
  exit 1
fi

echo "Ready at http://127.0.0.1:${HOST_PORT}"
echo "Health: http://127.0.0.1:${HOST_PORT}/actuator/health"
echo "Stop with: make kind-down"
