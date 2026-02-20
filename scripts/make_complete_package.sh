#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
PACKAGE_NAME="pedometer_complete_${TIMESTAMP}"
PACKAGE_DIR="$DIST_DIR/$PACKAGE_NAME"

JAVA_HOME_WIN_DEFAULT='C:\Program Files\Android\Android Studio\jbr'
JAVA_HOME_WIN="${JAVA_HOME_WIN:-$JAVA_HOME_WIN_DEFAULT}"

CMD_EXE="/mnt/c/Windows/System32/cmd.exe"

mkdir -p "$PACKAGE_DIR/docs"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  "$CMD_EXE" /c "set JAVA_HOME=$JAVA_HOME_WIN&& set PATH=%JAVA_HOME%\\bin;%PATH%&& gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease -Pandroid.injected.signing.store.file=C:\\Users\\minou\\.android\\debug.keystore -Pandroid.injected.signing.store.password=android -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android"
fi

DEBUG_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
AAB="$ROOT_DIR/app/build/outputs/bundle/release/app-release.aab"

for file in "$DEBUG_APK" "$RELEASE_APK" "$AAB"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing required artifact: $file" >&2
    exit 1
  fi
done

cp "$DEBUG_APK" "$PACKAGE_DIR/"
cp "$RELEASE_APK" "$PACKAGE_DIR/"
cp "$AAB" "$PACKAGE_DIR/"
cp "$ROOT_DIR/README.md" "$PACKAGE_DIR/docs/"
cp "$ROOT_DIR/CLAUDE.md" "$PACKAGE_DIR/docs/"
cp "$ROOT_DIR/CLAUDE_CODE_HANDOFF.md" "$PACKAGE_DIR/docs/"

cat > "$PACKAGE_DIR/README_PACKAGE.txt" <<EOF
PedometerApp Complete Package

Created: $(date -Iseconds)
Package: $PACKAGE_NAME

Files:
- app-debug.apk (debug build)
- app-release.apk (release build, signed with debug keystore)
- app-release.aab (release bundle)
- SHA256SUMS.txt
- BUILD_INFO.txt
- docs/ (project docs)

Notes:
- release APK is signed with Android debug keystore for immediate installation/testing.
- For production distribution, sign with your own release keystore.
EOF

GIT_REV="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo "no_commit")"
cat > "$PACKAGE_DIR/BUILD_INFO.txt" <<EOF
build_time=$(date -Iseconds)
git_revision=$GIT_REV
app_id=com.minou.pedometer
version_name=0.1.0
version_code=1
EOF

(
  cd "$PACKAGE_DIR"
  sha256sum app-debug.apk app-release.apk app-release.aab > SHA256SUMS.txt
)

ZIP_PATH="$DIST_DIR/${PACKAGE_NAME}.zip"

if command -v zip >/dev/null 2>&1; then
  (
    cd "$DIST_DIR"
    zip -qr "$ZIP_PATH" "$PACKAGE_NAME"
  )
  ARCHIVE_PATH="$ZIP_PATH"
else
  TAR_GZ_PATH="$DIST_DIR/${PACKAGE_NAME}.tar.gz"
  tar -C "$DIST_DIR" -czf "$TAR_GZ_PATH" "$PACKAGE_NAME"
  ARCHIVE_PATH="$TAR_GZ_PATH"
fi

echo "Package directory: $PACKAGE_DIR"
echo "Package archive: $ARCHIVE_PATH"
