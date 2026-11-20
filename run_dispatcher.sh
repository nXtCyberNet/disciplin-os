#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure port forward to ADB phone if connected via USB
if command -v adb >/dev/null 2>&1; then
    adb forward tcp:8080 tcp:8080 2>/dev/null || true
fi

echo "Starting Discipline Mission Dispatcher..."
exec python3 "$DIR/dispatcher/main.py" "$@"
