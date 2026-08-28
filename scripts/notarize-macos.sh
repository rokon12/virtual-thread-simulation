#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
app_version="${VT_MACHINE_VERSION:-1.0.0}"
app_version="${app_version#v}"

case "$(uname -m)" in
  x86_64|amd64) package_arch="x64" ;;
  arm64|aarch64) package_arch="arm64" ;;
  *) package_arch="$(uname -m | tr '[:upper:]' '[:lower:]')" ;;
esac

installer_path="${VT_MACHINE_DMG_PATH:-$project_dir/target/dist/Virtual-Thread-Machine-$app_version-macos-$package_arch.dmg}"

if [[ -z "${VT_MACHINE_NOTARY_PROFILE:-}" ]]; then
  echo "Set VT_MACHINE_NOTARY_PROFILE to an xcrun notarytool keychain profile." >&2
  exit 2
fi
if [[ ! -f "$installer_path" ]]; then
  echo "Package first with scripts/package.sh" >&2
  exit 2
fi

xcrun notarytool submit "$installer_path" --keychain-profile "$VT_MACHINE_NOTARY_PROFILE" --wait
xcrun stapler staple "$installer_path"
xcrun stapler validate "$installer_path"
echo "Notarized installer: $installer_path"
