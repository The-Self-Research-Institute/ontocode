#!/bin/bash
set -euo pipefail

usage() {
  cat <<'EOF'
Moves this server's database from PostgreSQL 17 to 19. Run it on the server, in the deploy directory.

  scripts/postgres-upgrade-19.sh --compose "-f docker-compose.de.ec2.yml" --rehearse
  scripts/postgres-upgrade-19.sh --compose "-f docker-compose.production.yml -f docker-compose.r6i-xlarge.yml"

--rehearse  copy the live database into a temporary PostgreSQL 19 container, compare every table,
            sequence and index, then remove the copy. Nothing in use is stopped or changed.
otherwise   stop the services that write to the database, keep a dump in backups/, restore into the
            new pgdata19 volume, compare everything, then replace the 17 container with the 19 one.
            The old volume is never written to.
EOF
}

COMPOSE=""
REHEARSE=0
OLD=ontocode-postgres
TEMP=ontocode-pg19-upgrade
WRITERS=(ontocode-auth ontocode-editor ontocode-plugin ontocode-reasoner-worker ontocode-swrl)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose) COMPOSE="$2"; shift 2 ;;
    --rehearse) REHEARSE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$COMPOSE" ]] || { usage >&2; exit 2; }

say() { echo "[$(date '+%H:%M:%S')] $*"; }
compose() { eval docker compose "$COMPOSE" "$@"; }

OLD_VERSION=$(docker exec "$OLD" sh -c 'cat "$PGDATA/PG_VERSION"')
[[ "$OLD_VERSION" == "17" ]] || { echo "$OLD runs PostgreSQL $OLD_VERSION, expected 17; nothing to do" >&2; exit 1; }
if [[ "$REHEARSE" == 0 ]]; then
  [[ "$(compose config --images 2>/dev/null | grep -c '^postgres:19$')" -ge 1 ]] \
    || { echo "the compose files do not use postgres:19 yet; deploy the new compose files first" >&2; exit 1; }
fi

PROJECT=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$OLD")
OLD_VOLUME=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$OLD")
NEW_VOLUME="${PROJECT}_pgdata19"
DB_USER=$(docker exec "$OLD" printenv POSTGRES_USER)
DB_NAME=$(docker exec "$OLD" sh -c 'echo "${POSTGRES_DB:-$POSTGRES_USER}"')
DB_PASSWORD=$(docker exec "$OLD" printenv POSTGRES_PASSWORD)
[[ "$REHEARSE" == 1 ]] && NEW_VOLUME="${PROJECT}_pgdata19_rehearsal"
say "project $PROJECT, old volume $OLD_VOLUME, new volume $NEW_VOLUME, database $DB_NAME"

if docker volume inspect "$NEW_VOLUME" >/dev/null 2>&1; then
  echo "volume $NEW_VOLUME already exists; remove it first if it is a leftover (docker volume rm $NEW_VOLUME)" >&2
  exit 1
fi
DB_BYTES=$(docker exec "$OLD" psql -U "$DB_USER" -d "$DB_NAME" -Atc "select pg_database_size(current_database())")
FREE_BYTES=$(( $(df --output=avail -B1 "$(docker info -f '{{.DockerRootDir}}')" | tail -1) ))
(( FREE_BYTES > 3 * DB_BYTES + 1073741824 )) || { echo "not enough free disk: database $DB_BYTES bytes, free $FREE_BYTES" >&2; exit 1; }

STOPPED=()
cleanup() {
  local code=$?
  docker rm -f "$TEMP" >/dev/null 2>&1 || true
  if [[ "$REHEARSE" == 1 || "${SWITCHED:-0}" == 0 ]]; then
    docker volume rm "$NEW_VOLUME" >/dev/null 2>&1 || true
  fi
  if [[ ${#STOPPED[@]} -gt 0 && "${SWITCHED:-0}" == 0 ]]; then
    say "starting the services again on the unchanged PostgreSQL 17 database"
    docker start "${STOPPED[@]}" >/dev/null || true
  fi
  if [[ "$code" != 0 && "${SWITCHED:-0}" == 1 ]]; then
    echo "The switch did not finish. To go back to PostgreSQL 17: restore the previous compose file" >&2
    echo "(postgres:17 with pgdata:/var/lib/postgresql/data), run: docker compose $COMPOSE up -d --no-deps postgres" >&2
    echo "then: docker start ${STOPPED[*]:-} && docker restart ontocode-gateway" >&2
  fi
}
trap cleanup EXIT

if [[ "$REHEARSE" == 0 ]]; then
  for c in "${WRITERS[@]}"; do
    if [[ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" == "true" ]]; then
      docker stop "$c" >/dev/null
      STOPPED+=("$c")
    fi
  done
  say "stopped ${STOPPED[*]:-nothing}"
fi

if [[ "$REHEARSE" == 1 ]]; then
  DUMP=$(mktemp /tmp/pg-rehearsal-XXXXXX.dump)
  trap 'rm -f "$DUMP" "$DUMP.part"; cleanup' EXIT
else
  mkdir -p backups
  DUMP="backups/postgres-${OLD_VERSION}-$(date +%Y%m%d-%H%M%S).dump"
fi
docker exec "$OLD" pg_dump -Fc -U "$DB_USER" -d "$DB_NAME" > "$DUMP.part"
mv "$DUMP.part" "$DUMP"
say "dump written: $DUMP ($(du -h "$DUMP" | cut -f1))"

docker volume create --label "com.docker.compose.project=$PROJECT" --label "com.docker.compose.volume=pgdata19" "$NEW_VOLUME" >/dev/null
docker run -d --name "$TEMP" --network none -v "$NEW_VOLUME:/var/lib/postgresql" \
  -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB_NAME" -e POSTGRES_PASSWORD="$DB_PASSWORD" postgres:19 >/dev/null
for _ in $(seq 1 120); do docker exec "$TEMP" pg_isready -U "$DB_USER" -d "$DB_NAME" -q 2>/dev/null && break; sleep 1; done
docker exec -i "$TEMP" pg_restore -U "$DB_USER" -d "$DB_NAME" --exit-on-error < "$DUMP"
docker exec "$TEMP" psql -U "$DB_USER" -d "$DB_NAME" -q -c "ANALYZE"
say "restored into PostgreSQL $(docker exec "$TEMP" sh -c 'cat "$PGDATA/PG_VERSION"')"

FINGERPRINT="select table_name,
 (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text,
 (xpath('/row/m/text()', query_to_xml(format('select md5(string_agg(md5(t::text), %L order by md5(t::text))) as m from %I.%I t', '', table_schema, table_name), false, true, '')))[1]::text
 from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1;
 select 'sequence ' || sequencename, coalesce(last_value::text, '-'), '' from pg_sequences order by 1;
 select 'index ' || indexname, '', '' from pg_indexes where schemaname = 'public' order by 1;"
OLD_FP=$(docker exec "$OLD" psql -U "$DB_USER" -d "$DB_NAME" -At -F ' ' -c "$FINGERPRINT")
NEW_FP=$(docker exec "$TEMP" psql -U "$DB_USER" -d "$DB_NAME" -At -F ' ' -c "$FINGERPRINT")
if [[ "$OLD_FP" != "$NEW_FP" ]]; then
  echo "the copy does not match the original:" >&2
  diff <(echo "$OLD_FP") <(echo "$NEW_FP") | head -20 >&2
  exit 1
fi
say "verified: $(echo "$OLD_FP" | grep -cvE '^(sequence|index) ') tables, $(echo "$OLD_FP" | grep -c '^index ') indexes and every sequence match"

if [[ "$REHEARSE" == 1 ]]; then
  say "rehearsal passed; the temporary copy is removed and nothing in use was changed"
  exit 0
fi

docker rm -f "$TEMP" >/dev/null
docker stop "$OLD" >/dev/null
docker rm "$OLD" >/dev/null
SWITCHED=1
compose up -d --no-deps postgres
for _ in $(seq 1 120); do [[ "$(docker inspect -f '{{.State.Health.Status}}' "$OLD" 2>/dev/null)" == "healthy" ]] && break; sleep 2; done
[[ "$(docker exec "$OLD" sh -c 'cat "$PGDATA/PG_VERSION"')" == "19" ]] || { echo "the new container is not on PostgreSQL 19" >&2; exit 1; }
say "PostgreSQL 19 is running in $OLD on volume $NEW_VOLUME"

[[ ${#STOPPED[@]} -gt 0 ]] && docker start "${STOPPED[@]}" >/dev/null
for c in "${STOPPED[@]}"; do
  for _ in $(seq 1 90); do
    s=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$c")
    [[ "$s" == "healthy" || "$s" == "running" ]] && break
    sleep 2
  done
  say "$c: $s"
done
docker restart ontocode-gateway >/dev/null
say "done. The PostgreSQL 17 volume $OLD_VOLUME and $DUMP are kept for rollback."
