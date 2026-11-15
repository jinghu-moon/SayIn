#!/system/bin/sh
MODDIR=${0%/*}
if [ -d /data/adb/sayin-helper ]; then
    "$MODDIR/bin/sayin-helper" disable || exit 1
    "$MODDIR/bin/sayin-helper" purge || exit 1
fi
echo "SayIn Helper stopped; owned clipboard data removed."
