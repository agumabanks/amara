#!/usr/bin/env bash
# Local recoverability for Cards PostgreSQL. Off-host storage is a separate gate.
set -euo pipefail
umask 077
PATH=/usr/sbin:/usr/bin:/sbin:/bin

backup_root=/var/backups/amara-cards
database=sanaa_cards
stamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="$backup_root/${database}_${stamp}.dump"

install -d -m 0700 "$backup_root"
free_kib=$(df -Pk "$backup_root" | awk 'NR == 2 { print $4 }')
if (( free_kib < 10 * 1024 * 1024 )); then
    printf 'Cards backup refused: less than 10 GiB free at %s\n' "$backup_root" >&2
    exit 1
fi
if [[ -e "$archive" ]]; then
    printf 'Cards backup refused: archive name already exists\n' >&2
    exit 1
fi

temporary=$(mktemp "$backup_root/.${database}_${stamp}.XXXXXX")
trap 'rm -f "$temporary"' EXIT
runuser -u postgres -- pg_dump -Fc --dbname="$database" > "$temporary"
test -s "$temporary"
pg_restore --list "$temporary" >/dev/null
mv "$temporary" "$archive"
trap - EXIT

( cd "$backup_root" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256" )
printf 'Cards local backup verified: %s (%s bytes)\n' "$archive" "$(stat -c %s "$archive")"

# Retire only this job's old copies, and only after a complete new archive exists.
find "$backup_root" -maxdepth 1 -type f -name "${database}_*.dump" -mtime +6 -print0 |
    while IFS= read -r -d '' old_archive; do
        rm -f -- "$old_archive" "$old_archive.sha256"
        printf 'Cards local backup expired: %s\n' "$old_archive"
    done
