#!/usr/bin/env sh
set -eu

if command -v clang >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1 || command -v cc >/dev/null 2>&1; then
    exit 0
fi

if command -v brew >/dev/null 2>&1; then
    brew install llvm
    exit 0
fi

if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y build-essential
    exit 0
fi

if command -v dnf >/dev/null 2>&1; then
    sudo dnf group install -y "Development Tools"
    exit 0
fi

if command -v pacman >/dev/null 2>&1; then
    sudo pacman -S --needed --noconfirm base-devel
    exit 0
fi

echo "No supported package manager found. Install clang or gcc manually." >&2
exit 1
