#!/usr/bin/env bash
# Run from the repository root; GRADLE may select the CI matrix distribution.
set -euo pipefail

repository="${1:?Usage: bash scripts/smoke-test-publication.sh /absolute/publication/repository}"
if [[ ! -d "$repository" ]]; then
  echo "Publication repository does not exist: $repository" >&2
  exit 1
fi
# Resolve before entering the independent consumer builds.
repository="$(cd "$repository" && pwd)"
version="$(sed -n 's/^pluginVersion=//p' gradle.properties | tr -d '\r')"
if [[ -z "$version" || "$version" == *$'\n'* ]]; then
  echo 'Expected exactly one nonempty pluginVersion in gradle.properties.' >&2
  exit 1
fi
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
for consumer in core spring-boot; do
  args=(-p "smoke-tests/$consumer" build "-PpluginVersion=$version"
    "-PpluginRepository=$repository" --configuration-cache
    --configuration-cache-problems=fail --no-daemon --console=plain)
  "${GRADLE:-./gradlew}" "${args[@]}"
  "${GRADLE:-./gradlew}" "${args[@]}" | tee "$log"
  if ! grep -Fq 'Configuration cache entry reused.' "$log"; then
    echo "Configuration cache was not reused for the $consumer publication consumer." >&2
    exit 1
  fi
done
