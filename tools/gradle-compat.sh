#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Jede Version bleibt ein einzelnes Argument; keine Auswertung von Eingaben als Shellcode.
args=(--no-daemon)
add_version() {
  local property="$1" value="$2"
  [[ -z "$value" ]] && return 0
  if [[ ! "$value" =~ ^[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}$ ]]; then
    printf 'Ungültige Version für %s\n' "$property" >&2
    exit 2
  fi
  args+=("-P${property}=${value}")
}
baseline_mc=$(sed -n 's/^minecraft_version=//p' gradle.properties | tr -d '\r')
if [[ -n "${TARGET_MC:-}" && "$TARGET_MC" != "$baseline_mc" && -z "${TARGET_FABRIC_API:-}" ]]; then
  printf 'Für eine andere Minecraft-Version muss die passende Fabric-API-Version angegeben werden.\n' >&2
  exit 2
fi
add_version minecraft_version "${TARGET_MC:-}"
add_version fabric_api_version "${TARGET_FABRIC_API:-}"
add_version loader_version "${TARGET_LOADER:-}"
add_version loom_version "${TARGET_LOOM:-}"
add_version java_version "${TARGET_JAVA:-}"
exec bash ./gradlew "${args[@]}" "$@"
