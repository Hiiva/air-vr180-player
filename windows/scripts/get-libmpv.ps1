param(
    [string]$Destination = "target\release"
)

$ErrorActionPreference = "Stop"
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
$asset = $release.assets |
    Where-Object { $_.name -like "mpv-dev-x86_64-*.7z" -and $_.name -notlike "*-v3-*" } |
    Select-Object -First 1

if ($null -eq $asset) {
    throw "No 64-bit mpv development asset found in the latest mpv-winbuild-cmake release."
}

$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
$archivePath = Join-Path $temporaryDirectory $asset.name
New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null

try {
    Write-Host "Downloading $($asset.name)"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archivePath
    & 7z x $archivePath "-o$temporaryDirectory" -y | Out-Null
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Copy-Item (Join-Path $temporaryDirectory "libmpv-2.dll") $Destination -Force
    Write-Host "Installed $(Join-Path $Destination "libmpv-2.dll")"
}
finally {
    if (Test-Path $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
