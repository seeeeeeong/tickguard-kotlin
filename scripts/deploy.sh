#!/usr/bin/env bash
# Deploys an image CI published (default: main) to the home server, and puts
# the previous one back if the new one does not reach Toss.
#
#   scripts/deploy.sh            # the latest main
#   scripts/deploy.sh <commit>   # a specific commit, e.g. to roll forward past a bad main
#
# "Up" means the stream is connected, not that the process answers /health:
# a process can be healthy while every request it makes is refused. So a
# deploy from an address Toss refuses fails and rolls back, and the one it
# rolls back to is refused too. The status page says why.
set -euo pipefail

host="${TICKGUARD_HOST:-root@100.127.216.1}"
status="${TICKGUARD_STATUS_URL:-http://100.127.216.1:9464}"
tag="${1:-main}"
# Two minutes: startup retries calendars and holdings before it subscribes.
wait_seconds="${TICKGUARD_DEPLOY_WAIT:-120}"

remote() { ssh -o BatchMode=yes "$host" "cd /opt/tickguard && $*"; }

connected() {
  curl -fs -m 5 "$status/health" >/dev/null && curl -fs -m 5 "$status/" | grep -q "connected "
}

echo "deploy: $tag to $host"

# The running image, kept under a local tag so rolling back needs no registry.
remote 'id=$(docker inspect -f "{{.Image}}" tickguard-tickguard-1 2>/dev/null || true);
        if [ -n "$id" ]; then docker tag "$id" ghcr.io/seeeeeeong/tickguard-kotlin:rollback; fi'

# The tag goes into .env, so a later `docker compose up -d` by hand keeps it.
remote "sed -i '/^TICKGUARD_TAG=/d' .env && echo TICKGUARD_TAG=$tag >> .env &&
        docker compose pull -q && docker compose up -d"

for _ in $(seq 1 "$((wait_seconds / 5))"); do
  if connected; then
    echo "deploy: $tag is up and connected"
    exit 0
  fi
  sleep 5
done

echo "deploy: $tag did not connect within ${wait_seconds}s; rolling back" >&2
remote 'docker compose logs --no-log-prefix --since 5m | grep -E "ERROR|WARN" | tail -20' >&2 || true
remote "sed -i '/^TICKGUARD_TAG=/d' .env && echo TICKGUARD_TAG=rollback >> .env &&
        docker compose up -d --pull never"
exit 1
