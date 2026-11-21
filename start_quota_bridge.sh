#!/usr/bin/env bash
# Run inside Debian, alongside quota_bridge.py. Reuses the existing pairing key.
set -eu
bridge_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
state_dir="$HOME/.sakatourin-widget"
umask 077
mkdir -p "$state_dir"
if [ "${1:-}" = "--supervise" ]; then
  exec 9>"$state_dir/bridge.lock"
  flock -n 9 || exit 0
  child=""
  trap '[ -z "$child" ] || kill "$child" 2>/dev/null; exit 0' TERM INT
  while :; do
    python3 -u "$bridge_dir/quota_bridge.py" &
    child=$!
    wait "$child" || true
    child=""
    sleep 3
  done
fi
nohup bash "$bridge_dir/start_quota_bridge.sh" --supervise >"$state_dir/bridge.log" 2>&1 </dev/null &
printf '本地读取服务已在后台启动；已运行时不会重复启动。\n'
printf '沿用原有配对密钥。Android 仍需允许 Termux 后台运行。\n'
