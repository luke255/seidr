#!/usr/bin/env bash
# Signs a debug build in, or adds a kid to it, without typing on the phone.
# Passwords come from a file (or stdin with -), never the command line.
#
#   scripts/debug-account.sh -s SERIAL signin URL USER PASSFILE [KID_OF]
#   scripts/debug-account.sh -s SERIAL add-kid USER PASSFILE
#
# The debug app must be installed; it's started if needed. Prints the
# app's OK/FAILED line (logcat tag DebugAccount).
set -euo pipefail
PKG=eu.bradshaw.seidr.debug
[[ ${1:-} == -s ]] || { sed -n '2,10p' "$0"; exit 2; }
SERIAL=$2; shift 2
CMD=${1:?command}; shift
adb() { command adb -s "$SERIAL" "$@"; }
read_pass() { if [[ $1 == - ]]; then cat; else cat "$1"; fi | head -n1 | tr -d '\r\n'; }

case $CMD in
  signin)  URL=$1; USER=$2; PASS=$(read_pass "$3"); KIDOF=${4:-} ;;
  add-kid) URL=; USER=$1; PASS=$(read_pass "$2"); KIDOF= ;;
  *) echo "unknown command $CMD" >&2; exit 2 ;;
esac

JSON=$(python3 -c 'import json,sys; c,u,n,p,k=sys.argv[1:]; print(json.dumps({"cmd":c,"url":u or None,"username":n,"password":p,"kidOf":k or None}))' \
  "$CMD" "$URL" "$USER" "$PASS" "$KIDOF")

# Into app-private storage via run-as; the app deletes it on read.
printf '%s' "$JSON" | adb shell "run-as $PKG sh -c 'cat > files/debug-account.json'"
adb logcat -c
adb shell am broadcast -a eu.bradshaw.seidr.DEBUG_ACCOUNT -n "$PKG/eu.bradshaw.seidr.DebugAccountReceiver" -f 0x20 >/dev/null
for _ in $(seq 1 30); do
  line=$(adb logcat -d -s DebugAccount:I | grep -E 'OK:|FAILED:' | tail -n1 || true)
  if [[ -n $line ]]; then echo "${line#*: }"; [[ $line == *OK:* ]]; exit; fi
  sleep 1
done
echo "no answer from the app (is $PKG installed?)" >&2; exit 1
