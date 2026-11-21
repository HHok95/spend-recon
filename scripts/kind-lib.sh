#!/usr/bin/env bash
# Shared helpers for the local kind cluster.
# Sourced by kind-up.sh, kind-down.sh, and the verify script.
# The caller sets ROOT to the repository root and enables set -euo pipefail.

# kind 0.23.0 accepts kubeProxyMode: nftables, but its default node image is
# Kubernetes 1.30. nftables kube-proxy runs without a feature gate on
# Kubernetes 1.31. kind 0.24.0 is the first release whose default node is 1.31.
KIND_MIN_VERSION="0.24.0"

# Written by kind-up.sh onto every node after it creates the cluster.
# kind-down.sh deletes a cluster only when this label is present.
KIND_MARKER_KEY="spend-recon.io/created-by"
KIND_MARKER_VALUE="kind-up.sh"

SPEND_RECON_NAMESPACE="spend-recon"

kind_cluster_name() {
  local name="${SPEND_RECON_KIND_CLUSTER:-spend-recon}"
  if [[ ! "$name" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || [[ ${#name} -gt 63 ]]; then
    echo "SPEND_RECON_KIND_CLUSTER must be a lowercase DNS label. Got: ${name}" >&2
    return 1
  fi
  printf '%s\n' "$name"
}

kind_context_name() {
  printf 'kind-%s\n' "$(kind_cluster_name)"
}

kind_kubeconfig_file() {
  if [[ -n "${SPEND_RECON_KUBECONFIG:-}" ]]; then
    printf '%s\n' "$SPEND_RECON_KUBECONFIG"
    return 0
  fi
  printf '%s\n' "$ROOT/.kube/kind-config"
}

kind_pf_pid_file() {
  printf '/tmp/spend-recon-kind-%s.port-forward.pid\n' "$(kind_cluster_name)"
}

kind_pf_log_file() {
  printf '/tmp/spend-recon-kind-%s.port-forward.log\n' "$(kind_cluster_name)"
}

# pkill -f treats the argument as a regular expression.
kind_port_forward_pattern() {
  local kubeconfig escaped
  kubeconfig="$(kind_kubeconfig_file)"
  escaped="$(printf '%s' "$kubeconfig" | sed 's/[.[\*^$()+?{|]/\\&/g')"
  printf 'kubectl --kubeconfig %s --context %s -n %s port-forward --address 127.0.0.1 svc/spend-recon %s:8080\n' \
    "$escaped" "$(kind_context_name)" "$SPEND_RECON_NAMESPACE" "${KIND_HOST_PORT:-8080}"
}

kind_version_tuple() {
  local text
  text="$(kind version 2>/dev/null || true)"
  if [[ "$text" =~ ([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
    printf '%s %s %s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    return 0
  fi
  return 1
}

kind_require_version() {
  local have major minor patch
  if ! have="$(kind_version_tuple)"; then
    echo "Could not read the kind version. Install kind ${KIND_MIN_VERSION} or newer." >&2
    return 1
  fi
  read -r major minor patch <<<"$have"
  if ((10#$major == 0 && 10#$minor < 24)); then
    echo "kind ${major}.${minor}.${patch} is older than ${KIND_MIN_VERSION}." >&2
    echo "scripts/kind-config.yaml sets kube-proxy to nftables." >&2
    echo "kind ${KIND_MIN_VERSION} is the first release whose default node image is Kubernetes 1.31, where nftables kube-proxy runs without a feature gate." >&2
    echo "Install kind ${KIND_MIN_VERSION} or newer, then run this script again." >&2
    return 1
  fi
}

kind_cluster_exists() {
  local name
  name="$(kind_cluster_name)"
  kind get clusters 2>/dev/null | grep -Fqx "$name"
}

kind_refresh_kubeconfig() {
  local dest dir tmp
  dest="$(kind_kubeconfig_file)"
  dir="$(dirname "$dest")"
  mkdir -p "$dir"
  tmp="${dest}.tmp"
  kind get kubeconfig --name "$(kind_cluster_name)" >"$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$dest"
}

kind_kubectl() {
  kubectl --kubeconfig "$(kind_kubeconfig_file)" --context "$(kind_context_name)" "$@"
}

# Prints present, absent, or unreadable.
kind_marker_status() {
  local values err rc
  err="$(mktemp)"
  set +e
  values="$(kind_kubectl get nodes -o 'go-template={{range .items}}{{index .metadata.labels "spend-recon.io/created-by"}}{{"\n"}}{{end}}' 2>"$err")"
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    rm -f "$err"
    printf 'unreadable\n'
    return 0
  fi
  rm -f "$err"
  values="${values//$'\n'/ }"
  if [[ " ${values} " == *" ${KIND_MARKER_VALUE} "* ]]; then
    printf 'present\n'
  else
    printf 'absent\n'
  fi
}

# Prints missing, owned, or foreign. Returns 1 when the cluster exists but
# its nodes cannot be read. A missing cluster is not an error.
kind_ownership() {
  local status
  if ! kind_cluster_exists; then
    printf 'missing\n'
    return 0
  fi
  kind_refresh_kubeconfig
  status="$(kind_marker_status)"
  case "$status" in
    present)
      printf 'owned\n'
      ;;
    absent)
      printf 'foreign\n'
      ;;
    unreadable)
      echo "Cluster $(kind_cluster_name) exists, but its nodes could not be read." >&2
      echo "Refusing to continue. This script will not reuse or delete a cluster it cannot identify." >&2
      return 1
      ;;
    *)
      echo "Unexpected cluster ownership result: ${status}" >&2
      return 1
      ;;
  esac
}

kind_refuse_foreign() {
  local action="$1"
  local name
  name="$(kind_cluster_name)"
  echo "Cluster ${name} was not created by scripts/kind-up.sh." >&2
  echo "None of its nodes has the label ${KIND_MARKER_KEY}=${KIND_MARKER_VALUE}." >&2
  echo "Refusing to ${action} it." >&2
  echo "Set SPEND_RECON_KIND_CLUSTER to a different name, or leave this cluster alone." >&2
  return 1
}

kind_mark_nodes() {
  kind_kubectl label nodes --all "${KIND_MARKER_KEY}=${KIND_MARKER_VALUE}" --overwrite >/dev/null
  [[ "$(kind_marker_status)" == "present" ]]
}
