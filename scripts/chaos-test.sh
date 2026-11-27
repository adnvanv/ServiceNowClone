#!/usr/bin/env bash
# Resilience check: keep authenticated API traffic flowing, delete one backend
# pod in the middle of it, and require that not a single request failed.
#
# What makes this pass (and what to say about it in an interview):
#   - 3 replicas behind a Service, so losing one leaves two serving
#   - readiness probe: the replacement only receives traffic once it is ready
#   - preStop sleep: the dying pod keeps serving while endpoint removal
#     propagates to the ingress controller
#   - server.shutdown=graceful: in-flight requests finish before the JVM exits
#   - stateless JWT auth: any replica can serve any user's next request
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
NAMESPACE="${NAMESPACE:-glidelike}"
RELEASE="${RELEASE:-glidelike}"
KUBE_CONTEXT="${KUBE_CONTEXT:-}"
WORKERS="${WORKERS:-4}"
API="$BASE_URL/api/now"
DEPLOY="$RELEASE-backend"
SELECTOR="app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/component=backend"

k() { if [ -n "$KUBE_CONTEXT" ]; then kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" "$@"; else kubectl -n "$NAMESPACE" "$@"; fi; }

token=$(curl -fsS "$API/auth/login" -H 'Content-Type: application/json' \
         -d '{"username":"admin","password":"admin"}' \
         | sed -nE 's/.*"token"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p')
[ -n "$token" ] || { echo "login failed"; exit 1; }

workdir=$(mktemp -d)
stop="$workdir/stop"

# Each worker hammers a read endpoint until told to stop, logging status codes.
worker() {
  local out="$workdir/codes.$1"
  while [ ! -f "$stop" ]; do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 \
      "$API/table/incident?sysparm_limit=5&sysparm_query=active=true" \
      -H "Authorization: Bearer $token" >> "$out" || echo "000" >> "$out"
  done
}

echo "Backend pods before:"
k get pods -l "$SELECTOR" -o wide

for i in $(seq 1 "$WORKERS"); do worker "$i" & done
sleep 5

victim=$(k get pods -l "$SELECTOR" -o jsonpath='{.items[0].metadata.name}')
echo "Deleting backend pod $victim while $WORKERS workers send traffic..."
k delete pod "$victim" --wait=false

# Let the Deployment replace it and become ready, then keep the load going a bit.
sleep 3
k rollout status "deploy/$DEPLOY" --timeout 180s
k wait --for=condition=Ready pod -l "$SELECTOR" --timeout 180s
sleep 5
touch "$stop"
wait

cat "$workdir"/codes.* > "$workdir/all"
total=$(wc -l < "$workdir/all" | tr -d ' ')
ok=$(grep -c '^200$' "$workdir/all" || true)
bad=$((total - ok))

echo "Backend pods after:"
k get pods -l "$SELECTOR" -o wide
echo "Requests: $total total, $ok succeeded, $bad failed"
if [ "$bad" -ne 0 ]; then
  echo "Non-200 status codes seen:"; grep -v '^200$' "$workdir/all" | sort | uniq -c
  exit 1
fi
if k get pod "$victim" >/dev/null 2>&1; then
  phase=$(k get pod "$victim" -o jsonpath='{.metadata.deletionTimestamp}')
  [ -n "$phase" ] || { echo "victim pod $victim was not deleted"; exit 1; }
fi
echo "Chaos test passed: pod $victim deleted with zero failed requests."
rm -rf "$workdir"
