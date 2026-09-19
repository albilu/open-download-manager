#!/bin/sh
set -eu
catalog_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:?Pass the resource output directory}
# An identity catalog makes an English entry in LANGUAGE stop the fallback list
# (for example en:fr), even though English messages originate in the source.
mkdir -p "$output_dir/locale/en/LC_MESSAGES"
temporary_dir=$(mktemp -d)
english_catalog="$temporary_dir/en.po"
trap 'rm -f "$english_catalog"; rmdir "$temporary_dir"' EXIT HUP INT TERM
msginit --no-translator --locale=en.UTF-8 --input="$catalog_dir/odm.pot" \
    --output-file="$english_catalog" >/dev/null
msgfmt --check --check-format -o "$output_dir/locale/en/LC_MESSAGES/odm.mo" "$english_catalog"
for catalog in "$catalog_dir"/*.po; do
    language=$(basename "$catalog" .po)
    mkdir -p "$output_dir/locale/$language/LC_MESSAGES"
    msgfmt --check --check-format -o "$output_dir/locale/$language/LC_MESSAGES/odm.mo" "$catalog"
done
