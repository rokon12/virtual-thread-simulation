#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
app_path="$project_dir/target/dist/Virtual Thread Machine.app"
archive_path="$project_dir/target/dist/Virtual-Thread-Machine.zip"

if [[ -z "${VT_MACHINE_NOTARY_PROFILE:-}" ]]; then
  echo "Set VT_MACHINE_NOTARY_PROFILE to an xcrun notarytool keychain profile." >&2
  exit 2
fi
if [[ ! -d "$app_path" ]]; then
  echo "Package first with scripts/package.sh" >&2
  exit 2
fi

ditto -c -k --keepParent "$app_path" "$archive_path"
xcrun notarytool submit "$archive_path" --keychain-profile "$VT_MACHINE_NOTARY_PROFILE" --wait
xcrun stapler staple "$app_path"
xcrun stapler validate "$app_path"
echo "Notarized application: $app_path"
