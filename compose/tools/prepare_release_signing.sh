#!/usr/bin/env bash
# Ephemeral evaluation key, explicitly chosen by the user. Not Android's debug key.
set -euo pipefail
: "${RUNNER_TEMP:?Run in CI or set RUNNER_TEMP to a private local directory}"
store="$RUNNER_TEMP/tux-compose-evaluation.p12"
password=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
echo "::add-mask::$password"
export TUX_RELEASE_STORE_PASSWORD="$password"
umask 077
keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$store" \
  -storepass:env TUX_RELEASE_STORE_PASSWORD -keypass:env TUX_RELEASE_STORE_PASSWORD \
  -alias tux-compose-evaluation -keyalg RSA -keysize 3072 -validity 3650 \
  -dname 'CN=Tux Compose Evaluation Release, OU=Evaluation Only, O=Tux Compose'
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'TUX_RELEASE_STORE_FILE=%s\nTUX_RELEASE_STORE_PASSWORD=%s\n' "$store" "$password" >> "$GITHUB_ENV"
else
  echo 'Set TUX_RELEASE_STORE_FILE and TUX_RELEASE_STORE_PASSWORD in your private local environment before assembling release.'
fi
