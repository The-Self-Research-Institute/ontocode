#!/usr/bin/env bash
set -euo pipefail

WEBHOOK_URL="${WEBHOOK_URL:-https://ontocodeapi.selfresearch.org/api/billing/webhook}"
ENV_FILE="${ENV_FILE:-.env}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"

EVENTS=(
  "checkout.session.completed"
  "customer.subscription.created"
  "customer.subscription.updated"
  "customer.subscription.deleted"
  "invoice.payment_succeeded"
  "invoice.payment_failed"
)

command -v curl >/dev/null || { echo "curl is required"; exit 1; }
command -v jq >/dev/null || { echo "jq is required: sudo apt-get install -y jq"; exit 1; }

if [[ -z "${STRIPE_SECRET_KEY:-}" ]]; then
  read -rsp "Enter Stripe secret key (sk_live_...): " STRIPE_SECRET_KEY
  echo
fi

if [[ ! "$STRIPE_SECRET_KEY" == sk_* ]]; then
  echo "Invalid Stripe secret key"
  exit 1
fi

echo "Creating Stripe webhook endpoint:"
echo "  $WEBHOOK_URL"

ARGS=(-u "$STRIPE_SECRET_KEY:" -d "url=$WEBHOOK_URL")
for event in "${EVENTS[@]}"; do
  ARGS+=(-d "enabled_events[]=$event")
done

RESPONSE="$(curl -sS https://api.stripe.com/v1/webhook_endpoints "${ARGS[@]}")"

WEBHOOK_ID="$(echo "$RESPONSE" | jq -r '.id // empty')"
WEBHOOK_SECRET="$(echo "$RESPONSE" | jq -r '.secret // empty')"
ERROR_MSG="$(echo "$RESPONSE" | jq -r '.error.message // empty')"

if [[ -z "$WEBHOOK_ID" || -z "$WEBHOOK_SECRET" ]]; then
  echo "Failed to create webhook endpoint"
  [[ -n "$ERROR_MSG" ]] && echo "Stripe error: $ERROR_MSG"
  echo "$RESPONSE" | jq .
  exit 1
fi

echo "Created webhook: $WEBHOOK_ID"

touch "$ENV_FILE"
cp "$ENV_FILE" "$ENV_FILE.bak.$(date +%Y%m%d%H%M%S)"

set_env() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    echo "${key}=${value}" >> "$ENV_FILE"
  fi
}

set_env "STRIPE_WEBHOOK_SECRET" "$WEBHOOK_SECRET"
set_env "STRIPE_WEBHOOK_ENDPOINT_ID" "$WEBHOOK_ID"

echo "Updated $ENV_FILE with STRIPE_WEBHOOK_SECRET"

if [[ -f "$COMPOSE_FILE" ]] && ! grep -q "STRIPE_WEBHOOK_SECRET" "$COMPOSE_FILE"; then
  echo "WARNING: $COMPOSE_FILE does not pass STRIPE_WEBHOOK_SECRET to the auth container."
  echo "Add this under auth.environment:"
  echo "  STRIPE_WEBHOOK_SECRET: \${STRIPE_WEBHOOK_SECRET:-}"
fi

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  echo "Restarting auth container..."
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d auth

  echo "Checking auth env..."
  docker compose -f "$COMPOSE_FILE" exec -T auth sh -lc 'printenv STRIPE_WEBHOOK_SECRET | sed "s/.\{8\}$/********/"' || true
fi

echo "Done."
echo "Verify in Stripe Dashboard that endpoint is enabled:"
echo "  $WEBHOOK_URL"