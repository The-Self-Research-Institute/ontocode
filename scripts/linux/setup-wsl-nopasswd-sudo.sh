#!/usr/bin/env bash

set -euo pipefail

USER_NAME="$(id -un)"
DROPIN="/etc/sudoers.d/ontocode-${USER_NAME}"

echo "This configures passwordless sudo for user: ${USER_NAME}"
echo "You will be asked for your WSL password once now."
echo ""

TMP="$(mktemp)"
echo "${USER_NAME} ALL=(ALL) NOPASSWD:ALL" >"$TMP"
sudo cp "$TMP" "$DROPIN"
sudo chmod 440 "$DROPIN"
rm -f "$TMP"

if sudo -n true 2>/dev/null; then
  echo "[OK] Passwordless sudo works. Re-run build-and-push.cmd — no sudo prompt."
else
  echo "[FAIL] Still need a password. Check: sudo cat $DROPIN"
  exit 1
fi
