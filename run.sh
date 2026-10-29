#!/usr/bin/env bash
# Linux/VPS launcher. Same binary as run.ps1, no PowerShell needed.
set -euo pipefail
cd "$(dirname "$0")"

: "${BOT_USERNAME:=AIBot}"
: "${BOT_HOST:=51.195.191.92}"
: "${BOT_PORT:=10679}"
: "${BOT_PASSWORD:=ChangeMe123}"
: "${OLLAMA_API_KEY:=}"
: "${JAVA_BIN:=java}"
# Single log file; Main tees stdout/stderr into it. Set BOT_LOG_APPEND=1 to
# keep history across restarts instead of truncating.
: "${BOT_LOG:=$PWD/logs/bot.log}"
export BOT_LOG

exec "$JAVA_BIN" -Xms128m -Xmx512m -jar sfus.jar \
    "$BOT_USERNAME" "$BOT_HOST" "$BOT_PORT" "$BOT_PASSWORD" "$OLLAMA_API_KEY"
