#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
mvn clean package -Pnative-image

app_path="$project_dir/target/dist/Virtual Thread Machine.app"
if [[ "$(uname -s)" == "Darwin" && -n "${VT_MACHINE_SIGN_IDENTITY:-}" ]]; then
  codesign --force --deep --options runtime --timestamp \
    --sign "$VT_MACHINE_SIGN_IDENTITY" "$app_path"
  codesign --verify --deep --strict --verbose=2 "$app_path"
fi

echo "Native application: $project_dir/target/dist"
