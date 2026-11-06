#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/ontocode}"
KEEP_DAYS="${KEEP_DAYS:-14}"
PG_CONTAINER="${PG_CONTAINER:-ontocode-postgres}"
BLOBS_CONTAINER="${BLOBS_CONTAINER:-ontocode-auth}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ -z "${PGPASSWORD:-}" && -f "$ENV_FILE" ]]; then
  PGPASSWORD="$(grep -E '^POSTGRES_PASSWORD=' "$ENV_FILE" | head -n 1 | cut -d= -f2-)"
fi
if [[ -z "${PGPASSWORD:-}" ]]; then
  echo "ERROR: set PGPASSWORD or POSTGRES_PASSWORD in $ENV_FILE" >&2
  exit 1
fi
export PGPASSWORD

STAMP="$(date +%Y-%m-%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"
DB_FILE="$BACKUP_DIR/ontocode-db-$STAMP.dump"
BLOBS_FILE="$BACKUP_DIR/ontocode-blobs-$STAMP.tar.gz"

BLOBS_VOLUME="$(docker inspect "$BLOBS_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/data/blobs"}}{{.Name}}{{end}}{{end}}')"
if [[ -z "$BLOBS_VOLUME" ]]; then
  echo "ERROR: no volume mounted at /data/blobs in $BLOBS_CONTAINER" >&2
  exit 1
fi

echo "Dumping database to $DB_FILE"
docker exec -e PGPASSWORD "$PG_CONTAINER" pg_dump -U ontocode -d ontocode -Fc > "$DB_FILE.part"
mv "$DB_FILE.part" "$DB_FILE"

echo "Archiving blobs volume to $BLOBS_FILE"
docker run --rm -v "$BLOBS_VOLUME":/data/blobs:ro -v "$BACKUP_DIR":/backup alpine \
  tar -czf "/backup/$(basename "$BLOBS_FILE").part" -C /data/blobs .
mv "$BLOBS_FILE.part" "$BLOBS_FILE"

echo "Removing backups older than $KEEP_DAYS days"
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'ontocode-db-*.dump' -o -name 'ontocode-blobs-*.tar.gz' \) -mtime +"$KEEP_DAYS" -print -delete

echo "Backup finished: $DB_FILE and $BLOBS_FILE"
