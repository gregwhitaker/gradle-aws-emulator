#!/usr/bin/env bash
#
# Copyright 2026 Greg Whitaker
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

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
