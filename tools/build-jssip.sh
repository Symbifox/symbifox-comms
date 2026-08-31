#!/usr/bin/env bash
# Régénère app/src/main/assets/webphone/jssip.js depuis le paquet npm `jssip`,
# dans un conteneur node épinglé, pour que rien n'ait à être installé sur
# l'hôte et que deux machines rendent le même fichier.
#
# Les versions sont figées par tools/jssip/package-lock.json : `npm ci` refuse
# de s'en écarter. Le paquet obtenu est donc reproductible, et son empreinte
# est imprimée à la fin pour être comparée à celle consignée dans
# THIRD_PARTY.md.
set -euo pipefail
P="$(cd "$(dirname "$0")/.." && pwd)"
OUT="app/src/main/assets/webphone/jssip.js"
HOST_UID=$(id -u); HOST_GID=$(id -g)

docker run --rm -v "$P":/work -w /work/tools/jssip \
  -e HOST_UID="$HOST_UID" -e HOST_GID="$HOST_GID" \
  node:22-bookworm-slim bash -c "
    set -e
    npm ci --no-audit --no-fund
    node build.mjs /work/$OUT
    chown -R \${HOST_UID}:\${HOST_GID} /work/tools/jssip/node_modules /work/$OUT 2>/dev/null || true
  "

echo
echo ">> $OUT"
echo "   $(cd "$P" && sha256sum "$OUT")"
