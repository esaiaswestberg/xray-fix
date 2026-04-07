#!/bin/bash

set -e

# ── Args ──────────────────────────────────────────────────────────────────────
MC_VERSION=""
SERVER_TYPE=""
MANUAL_JAR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar) MANUAL_JAR="$2"; shift 2 ;;
        *)
            if [ -z "$MC_VERSION" ]; then MC_VERSION="$1"
            elif [ -z "$SERVER_TYPE" ]; then SERVER_TYPE="$1"
            else echo "Unexpected argument: $1"; exit 1
            fi
            shift ;;
    esac
done

if [ -z "$MC_VERSION" ] || { [ -z "$SERVER_TYPE" ] && [ -z "$MANUAL_JAR" ]; }; then
    echo "Usage: $0 <mc-version> <server-type>"
    echo "       $0 <mc-version> --jar <path/to/server.jar>"
    echo "  mc-version:  e.g. 1.21.1, 1.20.4"
    echo "  server-type: paper, folia, purpur, pufferfish, spigot, craftbukkit"
    exit 1
fi

if [ -z "$MANUAL_JAR" ] && [[ ! "$SERVER_TYPE" =~ ^(paper|folia|purpur|pufferfish|spigot|craftbukkit)$ ]]; then
    echo "Unsupported server type: $SERVER_TYPE (supported: paper, folia, purpur, pufferfish, spigot, craftbukkit)"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$SCRIPT_DIR/../plugin"

# ── Java ──────────────────────────────────────────────────────────────────────
# Maven (plugin build) always uses the latest available Java.
BUILD_JAVA=$(find /usr/lib/jvm -name "java" -path "*/bin/java" | sort -t- -k2 -V | tail -n1)
if [ -z "$BUILD_JAVA" ]; then
    echo "No Java installation found in /usr/lib/jvm"
    exit 1
fi
export JAVA_HOME="$(dirname "$(dirname "$BUILD_JAVA")")"
echo "Using Java for build: $BUILD_JAVA"

# The server requires a Java version matched to the MC version:
#   1.8  - 1.16.x → Java 8
#   1.17.x         → Java 16
#   1.18  - 1.20.4 → Java 17
#   1.20.5+        → Java 21
MC_MAJOR=$(echo "$MC_VERSION" | cut -d. -f1)
MC_MINOR=$(echo "$MC_VERSION" | cut -d. -f2)
MC_PATCH=$(echo "$MC_VERSION" | cut -d. -f3); MC_PATCH=${MC_PATCH:-0}
if   [ "$MC_MAJOR" -ne 1 ]; then
    # New Mojang versioning scheme (e.g. 26.1) — always requires Java 21
    REQUIRED_JAVA=21
elif [ "$MC_MINOR" -ge 21 ] || { [ "$MC_MINOR" -eq 20 ] && [ "$MC_PATCH" -ge 5 ]; }; then
    REQUIRED_JAVA=21
elif [ "$MC_MINOR" -ge 18 ]; then
    REQUIRED_JAVA=17
elif [ "$MC_MINOR" -eq 17 ]; then
    REQUIRED_JAVA=16
else
    REQUIRED_JAVA=8
fi

SERVER_JAVA=$(find /usr/lib/jvm -name "java" -path "*/bin/java" | grep "java-${REQUIRED_JAVA}" | sort -t- -k2 -V | tail -n1)
if [ -z "$SERVER_JAVA" ]; then
    echo "Java $REQUIRED_JAVA is required for MC $MC_VERSION but was not found in /usr/lib/jvm"
    exit 1
fi
echo "Using Java for server: $SERVER_JAVA"

# ── Server jar ────────────────────────────────────────────────────────────────
if [ -n "$MANUAL_JAR" ]; then
    if [ ! -f "$MANUAL_JAR" ]; then
        echo "Jar not found: $MANUAL_JAR"
        exit 1
    fi
    JAR_PATH="$(cd "$(dirname "$MANUAL_JAR")" && pwd)/$(basename "$MANUAL_JAR")"
    echo "Using manual jar: $JAR_PATH"

elif [[ "$SERVER_TYPE" == "paper" || "$SERVER_TYPE" == "folia" ]]; then
    # PaperMC API
    VERSIONS_JSON=$(curl -fsSL "https://api.papermc.io/v2/projects/$SERVER_TYPE")
    RESOLVED_VERSION=$(echo "$VERSIONS_JSON" | jq -r --arg v "$MC_VERSION" \
        'first(.versions[] | select(. == $v)) // ([.versions[] | select(startswith($v + "."))] | last) // empty')

    if [ -z "$RESOLVED_VERSION" ]; then
        echo "Version $MC_VERSION not found for $SERVER_TYPE (try --jar to specify a jar manually)"
        exit 1
    fi
    [ "$RESOLVED_VERSION" != "$MC_VERSION" ] && echo "Resolved $MC_VERSION → $RESOLVED_VERSION"

    echo "Fetching latest $SERVER_TYPE build for $RESOLVED_VERSION..."
    BUILDS_JSON=$(curl -fsSL "https://api.papermc.io/v2/projects/$SERVER_TYPE/versions/$RESOLVED_VERSION/builds")

    # Prefer stable → experimental → any available build
    BUILD_NUMBER=$(echo "$BUILDS_JSON" | jq -r '[.builds[] | select(.channel == "default")] | last | .build // empty')
    JAR_NAME=$(echo "$BUILDS_JSON"    | jq -r '[.builds[] | select(.channel == "default")] | last | .downloads.application.name // empty')

    if [ -z "$BUILD_NUMBER" ]; then
        echo "No stable build found, trying experimental..."
        BUILD_NUMBER=$(echo "$BUILDS_JSON" | jq -r '[.builds[] | select(.channel == "experimental")] | last | .build // empty')
        JAR_NAME=$(echo "$BUILDS_JSON"    | jq -r '[.builds[] | select(.channel == "experimental")] | last | .downloads.application.name // empty')
    fi

    if [ -z "$BUILD_NUMBER" ]; then
        echo "No experimental build found, using latest available..."
        BUILD_NUMBER=$(echo "$BUILDS_JSON" | jq -r '.builds | last | .build // empty')
        JAR_NAME=$(echo "$BUILDS_JSON"    | jq -r '.builds | last | .downloads.application.name // empty')
    fi

    if [ -z "$BUILD_NUMBER" ] || [ -z "$JAR_NAME" ]; then
        echo "Failed to fetch build info for $SERVER_TYPE $MC_VERSION"
        exit 1
    fi

    JAR_PATH="$SCRIPT_DIR/$JAR_NAME"
    if [ -f "$JAR_PATH" ]; then
        echo "Server jar already cached: $JAR_NAME"
    else
        echo "Downloading $JAR_NAME (build $BUILD_NUMBER)..."
        curl -fL -o "$JAR_PATH" \
            "https://api.papermc.io/v2/projects/$SERVER_TYPE/versions/$RESOLVED_VERSION/builds/$BUILD_NUMBER/downloads/$JAR_NAME"
        echo "Downloaded $JAR_NAME"
    fi

elif [[ "$SERVER_TYPE" == "purpur" ]]; then
    # Purpur API
    VERSIONS_JSON=$(curl -fsSL "https://api.purpurmc.org/v2/purpur")
    RESOLVED_VERSION=$(echo "$VERSIONS_JSON" | jq -r --arg v "$MC_VERSION" \
        'first(.versions[] | select(. == $v)) // ([.versions[] | select(startswith($v + "."))] | last) // empty')

    if [ -z "$RESOLVED_VERSION" ]; then
        echo "Version $MC_VERSION not found for purpur (try --jar to specify a jar manually)"
        exit 1
    fi
    [ "$RESOLVED_VERSION" != "$MC_VERSION" ] && echo "Resolved $MC_VERSION → $RESOLVED_VERSION"

    JAR_NAME="purpur-${RESOLVED_VERSION}-latest.jar"
    JAR_PATH="$SCRIPT_DIR/$JAR_NAME"
    if [ -f "$JAR_PATH" ]; then
        echo "Server jar already cached: $JAR_NAME"
    else
        echo "Downloading $JAR_NAME..."
        curl -fL -o "$JAR_PATH" "https://api.purpurmc.org/v2/purpur/$RESOLVED_VERSION/latest/download"
        echo "Downloaded $JAR_NAME"
    fi

elif [[ "$SERVER_TYPE" == "pufferfish" ]]; then
    # Pufferfish Jenkins CI
    MC_MAJOR_MINOR="${MC_MAJOR}.${MC_MINOR}"
    echo "Fetching latest pufferfish build for $MC_MAJOR_MINOR..."
    BUILD_JSON=$(curl -fsSL "https://ci.pufferfish.host/job/Pufferfish-${MC_MAJOR_MINOR}/lastSuccessfulBuild/api/json")

    BUILD_URL=$(echo "$BUILD_JSON" | jq -r '.url // empty')
    REL_PATH=$(echo "$BUILD_JSON"  | jq -r '.artifacts[0].relativePath // empty')
    JAR_NAME=$(echo "$BUILD_JSON"  | jq -r '.artifacts[0].fileName // empty')

    if [ -z "$BUILD_URL" ] || [ -z "$REL_PATH" ]; then
        echo "Failed to fetch pufferfish build info for $MC_MAJOR_MINOR"
        exit 1
    fi

    JAR_PATH="$SCRIPT_DIR/$JAR_NAME"
    if [ -f "$JAR_PATH" ]; then
        echo "Server jar already cached: $JAR_NAME"
    else
        echo "Downloading $JAR_NAME..."
        curl -fL -o "$JAR_PATH" "${BUILD_URL}artifact/${REL_PATH}"
        echo "Downloaded $JAR_NAME"
    fi

elif [[ "$SERVER_TYPE" == "spigot" || "$SERVER_TYPE" == "craftbukkit" ]]; then
    # BuildTools
    if ! command -v git &>/dev/null; then
        echo "Git is required to build $SERVER_TYPE but was not found in PATH"
        exit 1
    fi

    BUILDTOOLS_DIR="$SCRIPT_DIR/buildtools"
    mkdir -p "$BUILDTOOLS_DIR"
    BUILDTOOLS_JAR="$BUILDTOOLS_DIR/BuildTools.jar"

    if [ ! -f "$BUILDTOOLS_JAR" ]; then
        echo "Downloading BuildTools..."
        curl -fL -o "$BUILDTOOLS_JAR" \
            "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"
        echo "Downloaded BuildTools.jar"
    fi

    # Check if a jar from a previous build already exists (glob, since BuildTools
    # may resolve e.g. "1.21" → "1.21.1" internally)
    EXISTING_JAR=$(find "$SCRIPT_DIR" -maxdepth 1 -name "${SERVER_TYPE}-${MC_VERSION}*.jar" | sort -V | tail -n1)
    if [ -n "$EXISTING_JAR" ]; then
        JAR_PATH="$EXISTING_JAR"
        echo "Server jar already cached: $(basename "$JAR_PATH")"
    else
        echo "Building $SERVER_TYPE $MC_VERSION with BuildTools (this takes ~10 minutes)..."
        (cd "$BUILDTOOLS_DIR" && "$SERVER_JAVA" -jar BuildTools.jar \
            --rev "$MC_VERSION" \
            --compile "$SERVER_TYPE" \
            --output-dir "$SCRIPT_DIR")
        JAR_PATH=$(find "$SCRIPT_DIR" -maxdepth 1 -name "${SERVER_TYPE}-${MC_VERSION}*.jar" | sort -V | tail -n1)
        if [ -z "$JAR_PATH" ]; then
            echo "BuildTools finished but no ${SERVER_TYPE}-${MC_VERSION}*.jar was found in $SCRIPT_DIR"
            exit 1
        fi
        echo "Built $(basename "$JAR_PATH")"
    fi
fi

# ── Build plugin ───────────────────────────────────────────────────────────────
echo "Building plugin for MC $MC_VERSION..."
MC_API_VERSION="${MC_MAJOR}.${MC_MINOR}"
(cd "$PLUGIN_DIR" && mvn clean package -q -Dmc.version="$MC_VERSION" -Dmc.api-version="$MC_API_VERSION")
echo "Plugin built."

# ── Update plugins directory ───────────────────────────────────────────────────
mkdir -p "$SCRIPT_DIR/plugins"
rm -f "$SCRIPT_DIR/plugins/xrayfix-"*.jar
cp "$PLUGIN_DIR/target/xrayfix-1.0.0.jar" "$SCRIPT_DIR/plugins/xrayfix-mc${MC_VERSION}.jar"
echo "Plugin installed: xrayfix-mc${MC_VERSION}.jar"

# ── Wipe worlds ────────────────────────────────────────────────────────────────
echo "Removing world directories..."
rm -rf "$SCRIPT_DIR/world" "$SCRIPT_DIR/world_nether" "$SCRIPT_DIR/world_the_end"

# ── EULA ──────────────────────────────────────────────────────────────────────
if [ ! -f "$SCRIPT_DIR/eula.txt" ] || ! grep -q "eula=true" "$SCRIPT_DIR/eula.txt"; then
    echo "You must accept the Minecraft End User License Agreement to run this server."
    echo "Read it at: https://aka.ms/MinecraftEULA"
    read -rp "Do you accept the EULA? (yes/no): " EULA_RESPONSE

    if [[ "$EULA_RESPONSE" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        echo "eula=true" > "$SCRIPT_DIR/eula.txt"
        echo "EULA accepted."
    else
        echo "You must accept the EULA to run the server. Exiting."
        exit 1
    fi
fi

# ── Launch ────────────────────────────────────────────────────────────────────
cd "$SCRIPT_DIR"
"$SERVER_JAVA" -Xmx2G -jar "$JAR_PATH" nogui
