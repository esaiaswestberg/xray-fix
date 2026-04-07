#!/bin/bash

JAR="folia-1.21.11-14.jar"

# Download the server jar only if it's missing
if [ ! -f "$JAR" ]; then
    echo "Server jar not found, downloading..."
    curl -OJ https://fill-data.papermc.io/v1/objects/f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39/folia-1.21.11-14.jar
fi

# Prompt for EULA acceptance if not already accepted
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

# Find the latest Java version installed under /usr/lib/jvm
JAVA=$(find /usr/lib/jvm -name "java" -path "*/bin/java" | sort -t- -k2 -V | tail -n1)

if [ -z "$JAVA" ]; then
    echo "No Java installation found in /usr/lib/jvm"
    exit 1
fi

echo "Using Java: $JAVA"
"$JAVA" -Xmx2G -jar "$JAR" nogui