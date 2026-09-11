param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path $PSScriptRoot -Parent

$ToolchainDir  = Join-Path $RepoRoot "mingw64"
$GccPath       = Join-Path $ToolchainDir "bin\gcc.exe"

$Version = "GCC 16.2.0 + MinGW-w64 14.0.0 (UCRT, POSIX)"

$Url = "https://github.com/brechtsanders/winlibs_mingw/releases/download/16.2.0posix-14.0.0-ucrt-r1/winlibs-x86_64-posix-seh-gcc-16.2.0-mingw-w64ucrt-14.0.0-r1.zip"

$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) "rkotlin-toolchain"
$ZipPath = Join-Path $TempDir "mingw64.zip"
$ExtractDir = Join-Path $TempDir "extract"

Write-Host "RKotlin toolchain downloader"
Write-Host "Toolchain: $Version"
Write-Host ""

# Already installed
if ((Test-Path $GccPath) -and -not $Force) {
    Write-Host "Toolchain is already installed:"
    Write-Host "  $GccPath"
    Write-Host ""
    Write-Host "Use -Force to reinstall."
    exit 0
}

try {
    # Clean temporary directory
    if (Test-Path $TempDir) {
        Remove-Item $TempDir -Recurse -Force
    }

    New-Item -ItemType Directory -Path $TempDir | Out-Null
    New-Item -ItemType Directory -Path $ExtractDir | Out-Null
    New-Item -ItemType Directory -Path $ToolchainDir -Force | Out-Null

    Write-Host "Downloading MinGW-w64..."
    Invoke-WebRequest `
        -Uri $Url `
        -OutFile $ZipPath

    Write-Host "Extracting..."
    Expand-Archive `
        -Path $ZipPath `
        -DestinationPath $ExtractDir `
        -Force

    $ExtractedToolchain = Join-Path $ExtractDir "mingw64"

    if (-not (Test-Path $ExtractedToolchain)) {
        throw "Archive does not contain mingw64/"
    }

    # Replace previous toolchain
    if (Test-Path $ToolchainDir) {
        Write-Host "Removing old toolchain..."
        Remove-Item $ToolchainDir -Recurse -Force
    }

    Write-Host "Installing toolchain..."
    Move-Item `
        -Path $ExtractedToolchain `
        -Destination $ToolchainDir

    if (-not (Test-Path $GccPath)) {
        throw "gcc.exe was not found after extraction: $GccPath"
    }

    Write-Host ""
    Write-Host "Toolchain installed successfully."
    Write-Host "GCC:"
    Write-Host "  $GccPath"
    Write-Host ""

    & $GccPath --version

} finally {
    if (Test-Path $TempDir) {
        Remove-Item $TempDir -Recurse -Force
    }
}
