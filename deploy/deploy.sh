#!/usr/bin/env bash
# Usage: deploy/deploy.sh user@host   (the .env must already exist on the host)
set -euo pipefail
host="${1:?usage: deploy.sh <host>}"
cd "$(dirname "$0")/.."
ssh "$host" 'mkdir -p seatlock'
scp -r compose.prod.yaml deploy "$host:seatlock/"
ssh "$host" 'cd seatlock && docker compose -f compose.prod.yaml pull && docker compose -f compose.prod.yaml up -d'
