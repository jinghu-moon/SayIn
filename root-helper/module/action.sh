#!/system/bin/sh
MODDIR=${0%/*}
BIN="$MODDIR/bin/sayin-helper"
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
    echo "Enable the module and reboot before starting collection."
    exit 1
fi
if [ -f /data/adb/sayin-helper/enabled ]; then
    "$BIN" disable || exit 1
    echo "Clipboard collection is OFF. Encrypted history is preserved."
else
    echo "Enabling local clipboard history: sensitive-marked, locked and unsupported content is skipped."
    "$BIN" enable || exit 1
    nohup "$BIN" run --module-dir "$MODDIR" >/dev/null 2>&1 </dev/null &
    sleep 1
    "$BIN" status
    echo "App keepalive and SayIn panel integration are not included yet."
fi
