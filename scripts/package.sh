#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

app_name="Virtual Thread Machine"
app_slug="Virtual-Thread-Machine"
app_version="${VT_MACHINE_VERSION:-1.0.0}"
app_version="${app_version#v}"
vendor="Bazlur Rahman"
description="An interactive JavaFX visualization of Java virtual threads."
homepage="https://github.com/rokon12/virtual-thread-simulation"
dist_dir="$project_dir/target/dist"

if [[ ! "$app_version" =~ ^[0-9]+([.][0-9]+){0,2}$ ]]; then
  echo "VT_MACHINE_VERSION must be a numeric version such as 1.0.0." >&2
  exit 2
fi

case "$(uname -m)" in
  x86_64|amd64) package_arch="x64" ;;
  arm64|aarch64) package_arch="arm64" ;;
  *) package_arch="$(uname -m | tr '[:upper:]' '[:lower:]')" ;;
esac

mvn clean package -Pnative-image "-Dapp.version=$app_version"

case "$(uname -s)" in
  Darwin)
    app_path="$dist_dir/$app_name.app"
    generated_installer="$dist_dir/$app_name-$app_version.dmg"
    installer_path="$dist_dir/$app_slug-$app_version-macos-$package_arch.dmg"

    if [[ -n "${VT_MACHINE_SIGN_IDENTITY:-}" ]]; then
      codesign --force --deep --options runtime --timestamp \
        --sign "$VT_MACHINE_SIGN_IDENTITY" "$app_path"
      codesign --verify --deep --strict --verbose=2 "$app_path"
    fi

    rm -f "$generated_installer" "$installer_path"
    jpackage \
      --type dmg \
      --app-image "$app_path" \
      --name "$app_name" \
      --dest "$dist_dir" \
      --app-version "$app_version" \
      --vendor "$vendor" \
      --description "$description" \
      --about-url "$homepage"
    mv "$generated_installer" "$installer_path"
    hdiutil verify "$installer_path"
    ;;
  Linux)
    app_path="$dist_dir/$app_name"
    linux_package_name="virtual-thread-machine"
    installer_path="$dist_dir/$app_slug-$app_version-linux-$package_arch.deb"

    rm -f "$dist_dir/${linux_package_name}_${app_version}_"*.deb "$installer_path"
    jpackage \
      --type deb \
      --app-image "$app_path" \
      --name "$app_name" \
      --dest "$dist_dir" \
      --app-version "$app_version" \
      --vendor "$vendor" \
      --description "$description" \
      --about-url "$homepage" \
      --linux-package-name "$linux_package_name" \
      --linux-deb-maintainer "bazlur@bazlur.dev" \
      --linux-menu-group "Education" \
      --linux-app-category "Education" \
      --linux-shortcut

    shopt -s nullglob
    generated_installers=("$dist_dir/${linux_package_name}_${app_version}_"*.deb)
    shopt -u nullglob
    if [[ ${#generated_installers[@]} -ne 1 ]]; then
      echo "Expected one generated .deb, found ${#generated_installers[@]}." >&2
      exit 1
    fi
    mv "${generated_installers[0]}" "$installer_path"
    dpkg-deb --info "$installer_path" >/dev/null
    ;;
  *)
    echo "Use scripts/package.ps1 to build the Windows installer." >&2
    exit 2
    ;;
esac

echo "Installable package: $installer_path"
