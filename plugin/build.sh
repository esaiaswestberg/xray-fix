#!/usr/bin/env bash
set -euo pipefail

VERSIONS=""
OUT_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --versions)
            VERSIONS="$2"
            shift 2
            ;;
        --out)
            OUT_DIR="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ -z "$VERSIONS" || -z "$OUT_DIR" ]]; then
    echo "Usage: $0 --versions <version1,version2,...> --out <output-dir>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAVA=$(find /usr/lib/jvm -name "java" -path "*/bin/java" | sort -t- -k2 -V | tail -n1)
if [[ -z "$JAVA" ]]; then
    echo "No Java installation found in /usr/lib/jvm" >&2
    exit 1
fi
export JAVA_HOME="$(dirname "$(dirname "$JAVA")")"
echo "Using Java: $JAVA"

PLUGIN_VERSION="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
mkdir -p "$OUT_DIR"

FAILED=0
IFS=',' read -ra VERSION_LIST <<< "$VERSIONS"

for MC_VERSION in "${VERSION_LIST[@]}"; do
    echo "Building for Minecraft $MC_VERSION..."
    MC_API_VERSION=$(echo "$MC_VERSION" | cut -d. -f1-2)
    MC_MAJOR=$(echo "$MC_VERSION" | cut -d. -f1)
    MC_MINOR=$(echo "$MC_VERSION" | cut -d. -f2)
    MC_PATCH=$(echo "$MC_VERSION" | cut -d. -f3); MC_PATCH=${MC_PATCH:-0}
    if   [ "$MC_MAJOR" -ne 1 ] 2>/dev/null; then
        MC_JAVA_VERSION=21
    elif [ "$MC_MINOR" -ge 21 ] || { [ "$MC_MINOR" -eq 20 ] && [ "$MC_PATCH" -ge 5 ]; }; then
        MC_JAVA_VERSION=21
    elif [ "$MC_MINOR" -ge 18 ]; then
        MC_JAVA_VERSION=17
    elif [ "$MC_MINOR" -eq 17 ]; then
        MC_JAVA_VERSION=16
    else
        MC_JAVA_VERSION=8
    fi
    if mvn clean package -Dmc.version="$MC_VERSION" -Dmc.api-version="$MC_API_VERSION" -Dmc.java-version="$MC_JAVA_VERSION" -q; then
        SRC="target/xrayfix-${PLUGIN_VERSION}.jar"
        DEST="${OUT_DIR}/xrayfix-v${PLUGIN_VERSION}-mc${MC_VERSION}.jar"
        cp "$SRC" "$DEST"
        echo "  -> $DEST"
    else
        echo "  FAILED for $MC_VERSION" >&2
        FAILED=1
    fi
done

exit $FAILED
