#!/usr/bin/env bash
# Start a local kind cluster, load the app image, and forward the API to localhost.
# Local only. This does not deploy to a shared cluster.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${KIND_CLUSTER_NAME:-spend-recon}"
IMAGE="${KIND_APP_IMAGE:-spend-recon:local}"
NAMESPACE="spend-recon"
HOST_PORT="${KIND_HOST_PORT:-8080}"
PF_PID_FILE="/tmp/spend-recon-kind-${CLUSTER}.port-forward.pid"
PF_LOG="/tmp/spend-recon-kind-${CLUSTER}.port-forward.log"

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

# Build before the cluster exists so a failed image build does not leave a cluster,
# and the image build does not run beside the node.
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "Reusing kind cluster $CLUSTER"
  echo "kube-proxy mode comes from the existing cluster. make kind-down recreates it from scripts/kind-config.yaml."
else
  echo "Creating kind cluster $CLUSTER"
  kind create cluster --name "$CLUSTER" --config "$ROOT/scripts/kind-config.yaml" --wait 180s
fi

kubectl config use-context "kind-${CLUSTER}"

echo "Loading $IMAGE into $CLUSTER"
kind load docker-image "$IMAGE" --name "$CLUSTER"
# postgres:16-alpine is a public image. The node pulls it.
# kind load of that multi-arch image fails on Docker's containerd image store.

echo "Applying manifests"
kubectl apply -k "$ROOT/k8s"

echo "Waiting for Postgres"
kubectl -n "$NAMESPACE" rollout status deployment/postgres --timeout=180s
echo "Waiting for the app"
if ! kubectl -n "$NAMESPACE" rollout status deployment/spend-recon --timeout=300s; then
  kubectl -n "$NAMESPACE" describe deployment/spend-recon >&2 || true
  kubectl -n "$NAMESPACE" logs deployment/spend-recon --tail=120 >&2 || true
  exit 1
fi

# nohup and disown keep the forward alive after this script exits.
nohup kubectl -n "$NAMESPACE" port-forward --address 127.0.0.1 "svc/spend-recon" "${HOST_PORT}:8080" \
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
