#!/usr/bin/env bash
# Run the Postman "E2E Flow" against the live dev server, then hand any failure to
# the fixer via e2e_context.py. Never hard-fails on the newman step itself — the
# pass/fail verdict is computed from the JSON report so context is always built.
set -uo pipefail

BASE_URL="${BASE_URL:?BASE_URL required}"

# Wake the Render Free dev server if it has gone to sleep (cold start ~3 min).
echo "Waiting for dev server at ${BASE_URL} ..."
for i in $(seq 1 24); do
  status=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 "${BASE_URL}/actuator/health" 2>/dev/null || echo "000")
  if [ "$status" = "200" ]; then
    echo "Server is up (attempt ${i})"
    break
  fi
  if [ "$i" -eq 24 ]; then
    echo "Server did not respond after 240 s; running Newman anyway"
  else
    echo "Attempt ${i}: status=${status}, waiting 10 s..."
    sleep 10
  fi
done

# Public GO NFC-e QR (from RealGoiasFixtureTest) — used for the one live scan.
# Not secret; it's printed on the receipt. Override via E2E_QR_PAYLOAD if desired.
GO_QR='https://nfeweb.sefaz.go.gov.br/nfeweb/sites/nfce/danfeNFCe?p=52260793209765049205655290000050451048579174|2|1|1|B72997307EDA1BEE338F17AC2C7C1988C4960035'

newman run postman/economizai.postman_collection.json \
  --folder "E2E Flow" \
  --env-var "baseUrl=${BASE_URL}" \
  --env-var "adminEmail=${E2E_ADMIN_EMAIL:-}" \
  --env-var "adminPassword=${E2E_ADMIN_PASSWORD:-}" \
  --env-var "qrPayload=${E2E_QR_PAYLOAD:-$GO_QR}" \
  --env-var "testMailbox=alexandre@economizaai.app" \
  --reporters cli,json \
  --reporter-json-export newman.json \
  --timeout-request 30000 || true

python3 .github/scripts/e2e_context.py
