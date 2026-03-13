#!/usr/bin/env bash
set -euo pipefail

APP_PKG="com.vibe.talkingtimer.app"
WEAR_PKG="com.vibe.talkingtimer.wear"

usage() {
  cat <<'EOF'
Usage:
  tools/exact_alarm_appops.sh <action> [target]

Actions:
  allow      Set exact alarm appops to allow
  deny       Set exact alarm appops to deny
  default    Reset exact alarm appops to default
  check      Print exact alarm appops state
  settings   Open exact alarm settings screen

Targets:
  app        Phone app package
  wear       Wear app package
  both       Both packages (default)

Examples:
  tools/exact_alarm_appops.sh allow wear
  tools/exact_alarm_appops.sh check both
  tools/exact_alarm_appops.sh settings app
EOF
}

action="${1:-}"
target="${2:-both}"

if [[ -z "$action" ]]; then
  usage
  exit 1
fi

packages=()
case "$target" in
  app) packages=("$APP_PKG") ;;
  wear) packages=("$WEAR_PKG") ;;
  both) packages=("$APP_PKG" "$WEAR_PKG") ;;
  *)
    echo "Unknown target: $target" >&2
    usage
    exit 1
    ;;
esac

run_appops() {
  local mode="$1"
  local pkg
  for pkg in "${packages[@]}"; do
    echo "adb shell appops set $pkg SCHEDULE_EXACT_ALARM $mode"
    adb shell appops set "$pkg" SCHEDULE_EXACT_ALARM "$mode"
  done
}

case "$action" in
  allow)
    run_appops allow
    ;;
  deny)
    run_appops deny
    ;;
  default)
    run_appops default
    ;;
  check)
    for pkg in "${packages[@]}"; do
      echo "adb shell appops get $pkg SCHEDULE_EXACT_ALARM"
      adb shell appops get "$pkg" SCHEDULE_EXACT_ALARM
    done
    ;;
  settings)
    if [[ "${#packages[@]}" -ne 1 ]]; then
      echo "Use target 'app' or 'wear' with settings." >&2
      exit 1
    fi
    pkg="${packages[0]}"
    echo "adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:$pkg"
    adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d "package:$pkg"
    ;;
  *)
    echo "Unknown action: $action" >&2
    usage
    exit 1
    ;;
esac
