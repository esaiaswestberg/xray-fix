#!/bin/bash

JAR="paper-1.21.11-128.jar"
DOWNLOAD_URL="https://fill-data.papermc.io/v1/objects/7a6774a582b1c24328b779854f43f2d3ac3bd2daeb5cedbbd1074f0871635a18/paper-1.21.11-128.jar"

# ── Download ──────────────────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "Server jar not found, downloading..."
    curl -OJ "$DOWNLOAD_URL"
fi

# ── EULA ──────────────────────────────────────────────────────────────────────
if [ ! -f "eula.txt" ] || ! grep -q "eula=true" eula.txt; then
    echo "You must accept the Minecraft End User License Agreement to run this server."
    echo "Read it at: https://aka.ms/MinecraftEULA"
    read -rp "Do you accept the EULA? (yes/no): " EULA_RESPONSE

    if [[ "$EULA_RESPONSE" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        echo "eula=true" > eula.txt
        echo "EULA accepted."
    else
        echo "You must accept the EULA to run the server. Exiting."
        exit 1
    fi
fi

# ── Java ──────────────────────────────────────────────────────────────────────
JAVA=$(find /usr/lib/jvm -name "java" -path "*/bin/java" | sort -t- -k2 -V | tail -n1)

if [ -z "$JAVA" ]; then
    echo "No Java installation found in /usr/lib/jvm"
    exit 1
fi

echo "Using Java: $JAVA"

# ── Launch ────────────────────────────────────────────────────────────────────
"$JAVA" -Xmx2G -jar "$JAR" nogui