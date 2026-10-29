#!/bin/bash
# tools/bot.sh - start, stop and restart the bot.
#
# Always stops the old process first. A bot killed with SIGKILL (or one that
# dies without a clean quit) leaves its session open on the server, and
# LoginSecurity then answers the next login with "This player is already
# online!" - which looks like a server problem but is really a leftover client.
# So restart waits for the server to release the session before giving up.
#
# Usage:
#   tools/bot.sh start            # start in the background
#   tools/bot.sh stop             # kill any running bot
#   tools/bot.sh restart          # stop, wait, start (the usual one)
#   tools/bot.sh status           # is it running?
#   tools/bot.sh cmd "craft me a wooden pickaxe"
#   tools/bot.sh log              # tail the log
#
# Connection details come from the environment, defaulting to the live server:
#   BOT_USER  BOT_HOST  BOT_PORT  BOT_PASS  BOT_DEBUG
set -u
cd "$(dirname "$0")/.." || exit 1

MAIN=mcbot.Main
LOG_DIR=logs
LOG="$LOG_DIR/bot.log"
CMD_FILE=bot.cmd

BOT_USER="${BOT_USER:-AIBot}"
BOT_HOST="${BOT_HOST:-51.195.191.92}"
BOT_PORT="${BOT_PORT:-10679}"
BOT_PASS="${BOT_PASS:-ChangeMe123}"

# Windows' java.exe is invisible to cygwin's pgrep, so ask the OS.
bot_pids() {
    MSYS_NO_PATHCONV=1 powershell -NoProfile -Command \
        "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$MAIN*' } | Select-Object -ExpandProperty ProcessId" \
        2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' || true
}

stop_bot() {
    local pids
    pids=$(bot_pids)
    if [ -z "$pids" ]; then
        echo "no bot running"
        return 0
    fi
    echo "killing bot pid(s): $(echo "$pids" | tr '\n' ' ')"
    MSYS_NO_PATHCONV=1 powershell -NoProfile -Command \
        "Stop-Process -Id $(echo "$pids" | tr '\n' ',' | sed 's/,$//') -Force -ErrorAction SilentlyContinue" \
        >/dev/null 2>&1
    for _ in $(seq 1 40); do
        if [ -z "$(bot_pids)" ]; then
            echo "stopped"
            return 0
        fi
        sleep 0.25
    done
    echo "warning: process still alive after 10s" >&2
    return 1
}

start_bot() {
    if [ ! -f out/mcbot/Main.class ] && [ ! -f sfus.jar ]; then
        echo "nothing built - run build.sh first" >&2
        return 1
    fi
    mkdir -p "$LOG_DIR"
    local debug_env=""
    if [ -n "${BOT_DEBUG:-}" ]; then debug_env="BOT_DEBUG=$BOT_DEBUG"; fi
    echo "starting $BOT_USER -> $BOT_HOST:$BOT_PORT"
    # Pass the key explicitly. The environment is inherited anyway, but a Git
    # Bash window opened before `setx OLLAMA_API_KEY` still carries the old
    # block, and that is how the bot ended up logging "chat is disabled" with
    # the key plainly set. reg.exe reads the hive itself, so it sees the new
    # value; OllamaClient has the same fallback for launchers that pass nothing.
    local key="${OLLAMA_API_KEY:-}"
    if [ -z "$key" ]; then
        key=$(MSYS_NO_PATHCONV=1 reg query 'HKCU\Environment' /v OLLAMA_API_KEY 2>/dev/null \
            | tr -d '\r' | awk '/REG_/ { $1=""; $2=""; sub(/^[ \t]+/, ""); print; exit }')
    fi
    # Main writes $BOT_LOG itself. The shell must not also redirect into that
    # file or every line would land twice; /dev/null stops nohup making a
    # nohup.out next to the source.
    export BOT_LOG="$PWD/$LOG"
    env BOT_CMD_FILE="$CMD_FILE" $debug_env \
        nohup java -cp out "$MAIN" "$BOT_USER" "$BOT_HOST" "$BOT_PORT" "$BOT_PASS" "$key" \
        > /dev/null 2>&1 &
    echo "pid $! -> $LOG"
}

# The server holds a dead client's session for a while. Retry rather than
# printing "already online" and leaving the operator to figure it out.
wait_for_login() {
    local tries="${1:-6}" i
    for i in $(seq 1 "$tries"); do
        sleep 20
        if grep -q "chunks loaded=" "$LOG" 2>/dev/null; then
            echo "connected"
            return 0
        fi
        if grep -q "already online" "$LOG" 2>/dev/null; then
            echo "session still held by the server, retrying ($i/$tries)"
            stop_bot >/dev/null
            sleep 25
            start_bot
            continue
        fi
        if grep -q "disconnected" "$LOG" 2>/dev/null; then
            echo "disconnected for another reason:"
            grep "disconnect" "$LOG" | tail -3
            return 1
        fi
    done
    echo "gave up waiting for login; see $LOG" >&2
    return 1
}

case "${1:-restart}" in
    start)
        if [ -n "$(bot_pids)" ]; then
            echo "already running (pid $(echo $(bot_pids))) - use restart" >&2
            exit 1
        fi
        start_bot
        ;;
    stop)
        stop_bot
        ;;
    restart)
        stop_bot || exit 1
        sleep 2
        start_bot
        wait_for_login "${2:-6}"
        ;;
    status)
        pids=$(bot_pids)
        if [ -z "$pids" ]; then
            echo "not running"
        else
            echo "running: $(echo "$pids" | tr '\n' ' ')"
        fi
        ;;
    cmd)
        shift
        if [ $# -eq 0 ]; then echo "usage: tools/bot.sh cmd \"<command>\"" >&2; exit 1; fi
        printf '%s' "$*" > "$CMD_FILE"
        echo "sent: $*"
        ;;
    log)
        tail -n "${2:-60}" "$LOG"
        ;;
    *)
        sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
