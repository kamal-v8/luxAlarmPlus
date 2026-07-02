#!/usr/bin/env bash
#
# release.sh — One-command release for Lux Alarm
#
# Usage: ./release.sh <version>
# Example: ./release.sh 2.3.2

set -euo pipefail

# Config
GRADLE_FILE="app/build.gradle.kts"
REMOTE="origin"
BRANCH="main"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# --- Helpers ---
error() { echo -e "${RED}ERROR: $1${NC}" >&2; exit 1; }
info() { echo -e "${GREEN}$1${NC}"; }
warn() { echo -e "${YELLOW}WARN: $1${NC}"; }

# --- Validate input ---
if [ $# -ne 1 ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 2.3.2"
    exit 1
fi

NEW_VERSION="$1"
TAG="v${NEW_VERSION}"

# Validate tarball-ish version string
if ! echo "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    error "Version must be in semantic format: X.Y.Z (e.g. 2.3.2)"
fi

# --- Validate repo state ---
if [ -n "$(git status --porcelain)" ]; then
    warn "Uncommitted changes detected:"
    git status --short
    read -rp "Continue anyway? [y/N] " confirm
    [[ "$confirm" =~ ^[Yy]$ ]] || exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "$BRANCH" ]; then
    error "Not on $BRANCH branch. Run: git checkout $BRANCH"
fi

# --- Parse current versions ---
CURRENT_VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" || true)
CURRENT_VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" || true)

if [ -z "$CURRENT_VERSION_CODE" ] || [ -z "$CURRENT_VERSION_NAME" ]; then
    error "Could not parse versionCode/versionName from $GRADLE_FILE"
fi

NEXT_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

info "Current: versionName=$CURRENT_VERSION_NAME, versionCode=$CURRENT_VERSION_CODE"
info "Next:    SPELLING_VERSION=$NEW_VERSION, versionCode=$NEXT_VERSION_CODE"

# --- Confirm with user ---
echo ""
read -rp "Proceed with release $TAG? [y/N] " confirm
[[ "$confirm" =~ ^[Yy]$ ]] || { info "Aborted."; exit 0; }

# --- Update version in build file ---
sed -i "s/versionCode = $CURRENT_VERSION_CODE/versionCode = $NEXT_VERSION_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"$CURRENT_VERSION_NAME\"/versionName = \"$NEW_VERSION\"/" "GRADLE_FILE"

info "Updated $GRADLE_FILE: versionCode=$NEXT_VERSION_CODE, versionName=$NEW_VERSION"

# --- Stage, commit, push ---
git add "$GRADLE_FILE"
git commit -m "release: $TAG" -m "Bump versionCode to $NEXT_VERSION_CODE" -m "versionName: $CURRENT_VERSION_NAME → $NEW_VERSION"
git push "$REMOTE" "$BRANCH"

info "Pushed commit to $REMOTE/$BRANCH"

# --- Tag and push ---
git tag -a "$TAG" -m "Release $TAG"
git push "$REMOTE" "$TAG"

info "Pushed tag $TAG"

# --- Optional: auto-build ---
echo ""
read -rp "Build signed release APK now? [y/N] " build_now
if [[ "$build_now" =~ ^[Yy]$ ]]; then
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    if [ -f "$APK_PATH" ]; then
        SHA=$(sha256sum "$APK_PATH" | awk '{print $1}')
        info "Build complete: $APK_PATH"
        info "SHA256: $SHA"
    fi
fi

# --- Summary ---
echo ""
echo "========================================"
info "Release $TAG published!"
echo "========================================"
echo ""
echo "GitHub Release: https://github.com/kamal-v8/LuxAlarm/releases/tag/$TAG"
echo ""
