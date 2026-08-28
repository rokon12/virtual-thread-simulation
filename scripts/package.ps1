$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $projectDir

$appName = "Virtual Thread Machine"
$appSlug = "Virtual-Thread-Machine"
$appVersion = if ($env:VT_MACHINE_VERSION) { $env:VT_MACHINE_VERSION.TrimStart("v") } else { "1.0.0" }
$vendor = "Bazlur Rahman"
$description = "An interactive JavaFX visualization of Java virtual threads."
$homepage = "https://github.com/rokon12/virtual-thread-simulation"
$upgradeUuid = "3C7A6F5E-20D2-4AB4-AF2A-7D30B8A154C9"
$distDir = Join-Path $projectDir "target\dist"

if ($appVersion -notmatch '^[0-9]+(\.[0-9]+){0,2}$') {
    throw "VT_MACHINE_VERSION must be a numeric version such as 1.0.0."
}

$architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
$packageArch = switch ($architecture) {
    "X64" { "x64" }
    "Arm64" { "arm64" }
    default { $architecture.ToLowerInvariant() }
}

& mvn clean package -Pnative-image "-Dapp.version=$appVersion"
if ($LASTEXITCODE -ne 0) {
    throw "Maven packaging failed with exit code $LASTEXITCODE."
}

$appPath = Join-Path $distDir $appName
$generatedInstaller = Join-Path $distDir "$appName-$appVersion.msi"
$installerPath = Join-Path $distDir "$appSlug-$appVersion-windows-$packageArch.msi"

Remove-Item $generatedInstaller, $installerPath -Force -ErrorAction SilentlyContinue
& jpackage `
    --type msi `
    --app-image $appPath `
    --name $appName `
    --dest $distDir `
    --app-version $appVersion `
    --vendor $vendor `
    --description $description `
    --about-url $homepage `
    --win-menu `
    --win-menu-group $appName `
    --win-shortcut `
    --win-dir-chooser `
    --win-upgrade-uuid $upgradeUuid
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE."
}

if (-not (Test-Path $generatedInstaller -PathType Leaf)) {
    throw "jpackage did not create the expected installer: $generatedInstaller"
}

Move-Item $generatedInstaller $installerPath
$installer = Get-Item $installerPath
Write-Host "Installable package: $($installer.FullName) ($($installer.Length) bytes)"
