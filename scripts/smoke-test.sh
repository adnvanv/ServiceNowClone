#!/usr/bin/env bash
# End-to-end smoke test against a running deployment (kind, EKS, or compose).
#
#   1. log in and get a JWT
#   2. check the demo seed ran exactly once (50 incidents, not 50 x replicas)
#   3. create a brand-new table at runtime (CREATE TABLE under the hood)
#   4. insert a record and query it back with an encoded query
#   5. check the SPA is served
#
# Requests go through the Ingress, so consecutive calls land on different
# backend replicas: step 4 also proves a table created via one pod is visible
# from the others (the sys_schema_version cache sync).
#
# Only needs bash + curl (no jq), so it runs the same on a laptop and in CI.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
API="$BASE_URL/api/now"
TABLE="smoke_$(date +%s)_$RANDOM"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
# json_field <name> : first "name":"value" string in stdin
json_field() { sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/p" | head -n1; }

echo "Smoke test against $BASE_URL"

# --- 1. login (retry while the deployment is still settling) -------------
token=""
for _ in $(seq 1 60); do
  resp=$(curl -fsS "$API/auth/login" -H 'Content-Type: application/json' \
          -d '{"username":"admin","password":"admin"}' 2>/dev/null || true)
  token=$(printf '%s' "$resp" | json_field token)
  [ -n "$token" ] && break
  sleep 2
done
[ -n "$token" ] || fail "could not log in as admin"
pass "logged in as admin"
AUTH="Authorization: Bearer $token"

# --- 2. seed ran exactly once --------------------------------------------
total=$(curl -fsS -D - -o /dev/null "$API/table/incident?sysparm_limit=1" -H "$AUTH" \
        | tr -d '\r' | sed -nE 's/^[Xx]-[Tt]otal-[Cc]ount: *([0-9]+).*/\1/p')
[ "$total" = "50" ] || fail "expected 50 seeded incidents, got '${total:-none}' (seeder raced?)"
pass "demo seed present exactly once (50 incidents)"

# --- 3. create a table at runtime ----------------------------------------
code=$(curl -sS -o /tmp/smoke-create.json -w '%{http_code}' "$API/meta/table" \
  -H "$AUTH" -H 'Content-Type: application/json' -d "{
    \"name\": \"$TABLE\", \"label\": \"Smoke $TABLE\",
    \"fields\": [
      {\"element\": \"title\", \"label\": \"Title\", \"type\": \"string\", \"maxLength\": 80},
      {\"element\": \"weight\", \"label\": \"Weight\", \"type\": \"integer\"}
    ]}")
[ "$code" = "200" ] || [ "$code" = "201" ] || fail "create table returned $code: $(cat /tmp/smoke-create.json)"
pass "created table '$TABLE'"

# --- 4. insert + query (several times, to hit several replicas) ----------
code=$(curl -sS -o /tmp/smoke-insert.json -w '%{http_code}' "$API/table/$TABLE" \
  -H "$AUTH" -H 'Content-Type: application/json' -d '{"title": "hello-k8s", "weight": 42}')
[ "$code" = "200" ] || [ "$code" = "201" ] || fail "insert returned $code: $(cat /tmp/smoke-insert.json)"
sys_id=$(json_field sys_id < /tmp/smoke-insert.json)
[ -n "$sys_id" ] || fail "insert response had no sys_id"
pass "inserted record $sys_id"

for i in 1 2 3 4 5 6; do
  body=$(curl -fsS "$API/table/$TABLE?sysparm_query=title=hello-k8s%5Eweight%3E40" -H "$AUTH") \
    || fail "query #$i failed"
  printf '%s' "$body" | grep -q "\"sys_id\":\"$sys_id\"" || fail "query #$i did not return the record: $body"
done
pass "encoded query 'title=hello-k8s^weight>40' returns it (6/6 requests across replicas)"

# --- 5. frontend ---------------------------------------------------------
curl -fsS "$BASE_URL/" | grep -qi '<div id="root"' || fail "frontend did not serve the SPA"
pass "frontend serves the SPA"

echo "Smoke test passed."
