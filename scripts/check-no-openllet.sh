#!/usr/bin/env bash
set -u
cd "$(git rev-parse --show-toplevel)"
bad=0
exec_files=$(git ls-files | grep -vE '^(THIRD_PARTY_NOTICES\.md|docs/|.*\.md$|.*\.html$)')
hits=$(printf '%s\n' "$exec_files" | xargs -d '\n' grep -InE 'openllet|com\.clarkparsia|org\.mindswap\.pellet|pellet-' 2>/dev/null \
  | grep -vE 'ReasonerType\.java:[0-9]+:.*(OPENLLET|PELLET)|ReasonerTypeBindingTest\.java|scripts/check-no-openllet\.sh|^pom\.xml:[0-9]+:.*(ban-openllet-pellet|<exclude>)' || true)
if [ -n "$hits" ]; then
  echo "Openllet or Pellet found in executable or build files:"
  echo "$hits"
  bad=1
fi
if [ "$bad" -eq 0 ]; then
  echo "OK: no Openllet or Pellet in tracked build, source, script or configuration files"
fi
exit $bad
