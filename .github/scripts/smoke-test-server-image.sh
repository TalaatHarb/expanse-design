#!/usr/bin/env bash
# Starts an expanse-server image and checks authenticated readiness and that anonymous access is rejected.
# Usage: smoke-test-server-image.sh <image>
set -euo pipefail
image=${1:?image required}
token=$(openssl rand -hex 32)
name=expanse-smoke-$$
docker run -d --name "$name" -p 8765:8765 -e EXPANSE_API_TOKEN="$token" "$image" >/dev/null
trap 'docker rm -f "$name" >/dev/null' EXIT
for _ in $(seq 1 60); do
  if curl -fsS -m 5 -H "Authorization: Bearer $token" http://127.0.0.1:8765/api/v1/capabilities >/dev/null; then
    status=$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/api/v1/projects)
    echo "authenticated: ready, anonymous: $status"
    test "$status" = 401
    docker exec "$name" sh -c 'test -f /data/projects.mv.db && test "$(id -u)" != 0'
    echo "smoke test passed"
    exit 0
  fi
  sleep 2
done
docker logs "$name"
echo "server did not become ready" >&2
exit 1
