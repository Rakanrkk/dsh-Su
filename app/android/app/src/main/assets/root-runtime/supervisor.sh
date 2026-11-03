#!/system/bin/sh
# dsh-Su root runtime supervisor, protocol v1.
# App launches: su -c "exec /system/bin/sh <script> run --protocol 1 ... --"
# Startup frame: "argv <argc>\n" then argc NUL-terminated records relayed byte-exact
# through a FIFO to the engine chain; secrets never appear in argv or on disk.
# Events go to stderr; stdout is the engine stdout pipe.
# Test hooks (host mock only): DSH_SU_TEST_DIR, DSH_SU_TEST_FAKE_CG, DSH_SU_TEST_ENGINE.
set -u

INSTANCE=-
PROTOCOL=
PKG=
APP_UID=
ROOTFS=
LOADER=
LIBRARY_PATH=
CWD=
CG=
CG_FOUND=
STATEDIR=
CG_OWNERDIR=
OWNERDIR=
CGDIR=
LOCKFD=
LOCK_DIR=
SUP_ORIGIN=
ENGINE_PID=
ENGINE_PID_START=
ENGINE_PID_FILE=
ENGINE_EXIT=
FRAME_FIFO=
CONTROL_PID=
CONTROL_STATE=
STOP_KIND=
FINISHED=
FINISH_CLEANUP=
ARGC=
SCRIPT=$0

code_status() {
  case "$1" in
    ARGUMENT) echo 2 ;;
    PROTOCOL) echo 3 ;;
    ROOT_DENIED) echo 4 ;;
    ALREADY_RUNNING) echo 5 ;;
    CGROUP2_MISSING) echo 6 ;;
    CGROUP_KILL_MISSING) echo 7 ;;
    CGROUP_CREATE) echo 8 ;;
    CGROUP_JOIN) echo 9 ;;
    CGROUP_READ) echo 10 ;;
    UNSHARE) echo 11 ;;
    PROPAGATION) echo 12 ;;
    MOUNT) echo 13 ;;
    L2S_PRESENT) echo 14 ;;
    CHROOT) echo 15 ;;
    KILL_TIMEOUT) echo 16 ;;
    CLEANUP) echo 17 ;;
    *) echo 1 ;;
  esac
}

emit() {
  printf '[dsh-su] 1 %s %s\n' "$INSTANCE" "$*" >&2
}

arg_error() {
  die ARGUMENT "$1" not-started
}

die() {
  code=$1
  detail=$2
  cleanup=${3:-}
  if [ -z "$cleanup" ]; then    if [ -n "$ENGINE_PID" ]; then cleanup=incomplete; else cleanup=not-started; fi
  fi
  printf '[dsh-su] 1 %s error %s %s\n' "$INSTANCE" "$code" "$detail" >&2
  finish "$(code_status "$code")" "$cleanup"
}

cgroup_field() {
  sed -n "s/^$2 //p" "$1" 2>/dev/null | head -1
}

cgroup_root() {
  CG_FOUND=
  if [ -n "${DSH_SU_TEST_FAKE_CG:-}" ]; then
    [ -d "$DSH_SU_TEST_FAKE_CG" ] || return 1
    CG_FOUND=$DSH_SU_TEST_FAKE_CG
    return 0
  fi
  [ -r /proc/self/mountinfo ] && [ -s /proc/self/mountinfo ] || return 1
  CG_FOUND=$(awk '
    index($0, " - cgroup2 ") {
      prefix = substr($0, 1, index($0, " - cgroup2 ") - 1)
      if (split(prefix, fields, " ") >= 5 && fields[5] != "") {
        print fields[5]
        exit
      }
    }
  ' /proc/self/mountinfo 2>/dev/null)
  [ -n "$CG_FOUND" ] || return 1
  return 0
}

wait_engine_empty() {
  # 0=populated 0; 1=timeout; 2=read failure
  dir=$1
  [ -d "$dir" ] || return 0
  i=0
  while [ "$i" -lt 50 ]; do
    p=$(cgroup_field "$dir/cgroup.events" populated)
    [ -n "$p" ] || return 2
    [ "$p" = 0 ] && return 0
    sleep 0.1
    i=$((i + 1))
  done
  p=$(cgroup_field "$dir/cgroup.events" populated)
  [ -n "$p" ] || return 2
  [ "$p" = 0 ] && return 0
  return 1
}

pid_identity_ok() {
  [ -n "$ENGINE_PID" ] || return 1
  [ -r "/proc/$ENGINE_PID/stat" ] || return 1
  st=$(sed 's/.*) //' "/proc/$ENGINE_PID/stat" 2>/dev/null | awk '{print $22}')
  [ -n "$st" ] || return 1  [ -n "$ENGINE_PID_START" ] && [ "$st" = "$ENGINE_PID_START" ] || return 1
  grep -F "$CGDIR/engine" "/proc/$ENGINE_PID/cgroup" >/dev/null 2>&1 || return 1
  return 0
}
kill_engine_tree() {
  # 0 empty; 1 KILL_TIMEOUT; 2 CGROUP_READ; 3 CGROUP_KILL_MISSING
  dir=$1
  [ -d "$dir" ] || return 0
  if pid_identity_ok; then
    kill -TERM "$ENGINE_PID" 2>/dev/null
  fi
  wait_engine_empty "$dir"
  rc=$?
  [ "$rc" = 0 ] && return 0
  [ "$rc" = 2 ] && return 2
  [ -e "$dir/cgroup.kill" ] || return 3
  printf '1\n' > "$dir/cgroup.kill" 2>/dev/null
  wait_engine_empty "$dir"
  rc=$?
  [ "$rc" = 0 ] && return 0
  [ "$rc" = 2 ] && return 2
  return 1
}

remove_state_path() {
  target=$1
  [ -e "$target" ] || [ -L "$target" ] || return 0
  if [ -d "$target" ] && [ ! -L "$target" ]; then
    rmdir "$target" 2>/dev/null || return 1
  else
    rm -f "$target" 2>/dev/null || return 1
  fi
  return 0
}

rmdir_if_empty() {
  target=$1
  [ -d "$target" ] || return 0
  p=$(cgroup_field "$target/cgroup.events" populated)
  if [ -n "$p" ] && [ "$p" != 0 ]; then return 1; fi
  rmdir "$target" 2>/dev/null || return 1
  return 0
}

collect_old_instances() {
  cleanup_failed=0
  old=
  old_state=
  for old_state in "$OWNERDIR"/*; do
    [ -d "$old_state" ] || continue
    [ "$old_state" = "$OWNERDIR" ] && continue
    old=${old_state##*/}
    [ "$old" = "$INSTANCE" ] && continue
    rm -f "$CG_OWNERDIR/$old/propagation-ok" "$CG_OWNERDIR/$old/stage2-error" 2>/dev/null
    rm -f "$old_state/propagation-ok" "$old_state/stage2-error" 2>/dev/null
    kill_engine_tree "$CG_OWNERDIR/$old/engine"
    rc=$?
    if [ "$rc" != 0 ]; then cleanup_failed=1; continue; fi
    rmdir_if_empty "$CG_OWNERDIR/$old/engine" || { cleanup_failed=1; continue; }
    rmdir_if_empty "$CG_OWNERDIR/$old/sup" || { cleanup_failed=1; continue; }
    rmdir_if_empty "$CG_OWNERDIR/$old" || cleanup_failed=1
    remove_state_path "$old_state/engine.pid"
    remove_state_path "$old_state/control-state"
    remove_state_path "$old_state/engine-frame"
    remove_state_path "$old_state" || cleanup_failed=1
  done
  return 0
}

mountinfo_clean() {
  [ -s /proc/self/mountinfo ] || return 1
  mi=
  while IFS= read -r mi; do    [ -n "$mi" ] || return 1
    case "$mi" in
      *[[:space:]]shared:*|*[[:space:]]master:*) return 1 ;;
    esac
  done < /proc/self/mountinfo
  return 0
}

stage2_main() {
  # argv: stage2 --cgdir <dir> --rootfs <path> [--bind H G]... -- <guest argv...>
  CGROOT=
  ROOTFS=
  shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --cgdir) CGROOT=$2; shift 2 ;;
      --rootfs) ROOTFS=$2; shift 2 ;;
      --bind) shift 3 ;;
      --) shift; break ;;
      *) shift ;;
    esac
  done
  [ -n "$ROOTFS" ] || exit 90
  mountinfo_clean || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 92; }
  mount --bind /proc "$ROOTFS/proc" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 93; }
  mount --bind /sys "$ROOTFS/sys" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 94; }
  mount -o remount,ro,bind "$ROOTFS/sys" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 94; }
  mount --bind "$ROOTFS/var/lib/dsh-app/empty" "$ROOTFS/sys/fs/selinux" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 95; }
  mount -t tmpfs -o mode=755,nosuid tmpfs "$ROOTFS/dev" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 96; }
  for node in null zero full urandom tty; do
    mount --bind "/dev/$node" "$ROOTFS/dev/$node" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 96; }
  done
  mount --bind /dev/urandom "$ROOTFS/dev/random" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 96; }
  mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "$ROOTFS/dev/pts" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 97; }
  mount -t tmpfs -o mode=1777,nosuid tmpfs "$ROOTFS/dev/shm" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 98; }
  mount --bind /system "$ROOTFS/system" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 99; }
  mount --rbind /apex "$ROOTFS/apex" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 99; }
  mountinfo_clean || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 92; }
  [ -n "$CGROOT" ] && printf 0 > "$CGROOT/propagation-ok"
  mkdir -p "$ROOTFS/system" "$ROOTFS/apex" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/dev/pts" "$ROOTFS/dev/shm" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 96; }
  mkdir -p "$ROOTFS/tmp" || { [ -n "$CGROOT" ] && printf 1 > "$CGROOT/stage2-error"; exit 96; }
  chmod 1777 "$ROOTFS/tmp" 2>/dev/null || true
  exec chroot "$ROOTFS" "$@"
}

# stage2 entry inside the private mount namespace.
if [ "${1:-}" = stage2 ]; then
  stage2_main "$@"
fi
parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --protocol) [ $# -ge 2 ] || arg_error "missing protocol"; PROTOCOL=$2; shift 2 ;;
      --package) [ $# -ge 2 ] || arg_error "missing package"; PKG=$2; shift 2 ;;
      --app-uid) [ $# -ge 2 ] || arg_error "missing app uid"; APP_UID=$2; shift 2 ;;
      --instance) [ $# -ge 2 ] || arg_error "missing instance"; INSTANCE=$2; shift 2 ;;
      --rootfs) [ $# -ge 2 ] || arg_error "missing rootfs"; ROOTFS=$2; shift 2 ;;
      --loader) [ $# -ge 2 ] || arg_error "missing loader"; LOADER=$2; shift 2 ;;
      --library-path) [ $# -ge 2 ] || arg_error "missing library path"; LIBRARY_PATH=$2; shift 2 ;;      --cwd) [ $# -ge 2 ] || arg_error "missing cwd"; CWD=$2; shift 2 ;;
      --bind)
        [ $# -ge 3 ] || arg_error "--bind needs host and guest"
        BINDS="$BINDS $2 $3"
        shift 3 ;;
      --) shift; [ $# -gt 0 ] && arg_error "trailing args after --"; break ;;
      *) arg_error "unknown argument $1" ;;
    esac
  done
  return 0
}

validate_args() {
  [ "$PROTOCOL" = "1" ] || die PROTOCOL "unsupported protocol"
  [ -n "$PKG" ] || arg_error "package required"
  case "$APP_UID" in ""|*[!0-9]*) arg_error "invalid app uid" ;; esac
  case "$INSTANCE" in ""|*[!0-9a-fA-F-]*) arg_error "invalid instance" ;; esac
  [ -n "$ROOTFS" ] || arg_error "rootfs required"
  case "$ROOTFS" in /*) ;; *) arg_error "rootfs must be absolute" ;; esac
  [ -d "$ROOTFS" ] || arg_error "rootfs missing"
  [ -n "$LOADER" ] || arg_error "loader required"
  case "$LOADER" in "$ROOTFS"/*) ;; *) arg_error "loader must be inside rootfs" ;; esac
  [ -f "$LOADER" ] || arg_error "loader missing"
  [ -n "$LIBRARY_PATH" ] || arg_error "library path required"
  lp_entry=
  oldIFS=$IFS
  IFS=:
  for lp_entry in $LIBRARY_PATH; do
    case "$lp_entry" in "$ROOTFS"/*) ;; *) arg_error "library dir outside rootfs" ;; esac
    [ -d "$lp_entry" ] || arg_error "library dir missing"
  done
  IFS=$oldIFS
  [ -n "$CWD" ] || arg_error "cwd required"
  case "$CWD" in /*) ;; *) arg_error "cwd must be absolute" ;; esac
  l2s=
  for l2s in $(find "$ROOTFS" -name '*.l2s' 2>/dev/null | head -1); do    die L2S_PRESENT "$l2s"
  done
  return 0
}

acquire_lock() {
  LOCK_DIR=${DSH_SU_TEST_DIR:-$(dirname "$(dirname "$SCRIPT")")}
  OWNERDIR="$LOCK_DIR/dsh-su.state.$PKG.$APP_UID"
  mkdir -p "$OWNERDIR" || die CLEANUP "state dir create failed"
  LOCK_PATH="$OWNERDIR/instance.lock"
  case "$LOCK_PATH" in
    *[\']*) die ARGUMENT "unsafe state path" ;;
  esac
  eval "exec 9>>'$LOCK_PATH'"
  flock -x 9 || die ALREADY_RUNNING "lock busy"
  LOCKFD=9
  return 0
}

release_lock() {
  if [ -n "$LOCKFD" ]; then
    eval "exec $LOCKFD>&-" 2>/dev/null
    LOCKFD=
  fi
}

setup_cgroups() {
  cgroup_root || die CGROUP2_MISSING "no cgroup2 mount"
  CG=$CG_FOUND
  CG_OWNERDIR="$CG/dsh-su.$PKG.$APP_UID"
  CGDIR="$CG_OWNERDIR/$INSTANCE"
  if [ -e "$CGDIR" ]; then
    die ALREADY_RUNNING "instance dir exists"
  fi
  STATEDIR="$OWNERDIR/$INSTANCE"
  mkdir -p "$STATEDIR" || die CLEANUP "instance state create failed"
  collect_old_instances
  [ "$cleanup_failed" = 0 ] || die CLEANUP "old instance recovery failed"
  mkdir -p "$CGDIR/sup" "$CGDIR/engine" || die CGROUP_CREATE "cgroup mkdir failed"
  printf '0\n' > "$CGDIR/sup/cgroup.procs" || die CGROUP_JOIN "supervisor join failed"
  SUP_ORIGIN="$CGDIR/sup"
  return 0
}
start_engine() {
  # argv: start_engine <frame_fifo> --cgdir <dir> --rootfs <path> [--bind H G]... -- <guest argv...>
  frame_fifo=$1
  shift
  if [ -n "${DSH_SU_TEST_ENGINE:-}" ]; then
    "$DSH_SU_TEST_ENGINE" "$@" < "$frame_fifo" &
  else
    "$LOADER" --library-path "$LIBRARY_PATH" "$ROOTFS/usr/bin/unshare" \
      --mount --propagation private -- /system/bin/sh "$SCRIPT" stage2 "$@" \
      < "$frame_fifo" &
  fi
  ENGINE_PID=$!
  ENGINE_PID_START=$(awk '{print $22}' "/proc/$ENGINE_PID/stat" 2>/dev/null)
  ENGINE_PID_FILE="$STATEDIR/engine.pid"
  printf '%s\n' "$ENGINE_PID" > "$ENGINE_PID_FILE"
  return 0
}

relay_frame() {
  # relay argv <argc> + records from stdin to fifo fd8, byte-exact, no shell cache.
  header=
  IFS= read -r header || die PROTOCOL "startup frame header missing"
  case "$header" in
    argv\ *) ARGC=${header#argv } ;;
    *) die PROTOCOL "bad startup frame header" ;;
  esac
  case "$ARGC" in ''|0|*[!0-9]*) die PROTOCOL "invalid argc" ;; esac
  printf 'argv %s\n' "$ARGC" >&8
  index=0
  while [ "$index" -lt "$ARGC" ]; do
    REPLY=
    IFS= read -r -d '' REPLY || die PROTOCOL "startup frame incomplete"
    printf '%s\0' "$REPLY" >&8
    index=$((index + 1))
  done
  return 0
}

control_loop() {
  # consume LF-terminated control lines in background; EOF or stop sets state.
  (
    stop_kind=eof
    control=
    while IFS= read -r control; do
      if [ "$control" = stop ]; then stop_kind=stop; break; fi
      printf '[dsh-su] 1 %s error PROTOCOL control\n' "$INSTANCE" >&2
    done
    printf '%s\n' "$stop_kind" > "$STATEDIR/control-state"
  ) &
  CONTROL_PID=$!
  return 0
}

stop_engine() {
  kill_engine_tree "$CGDIR/engine"
  rc=$?
  [ "$rc" = 1 ] && die KILL_TIMEOUT "engine kill timeout" incomplete
  [ "$rc" = 2 ] && die CGROUP_READ "cgroup read failed" incomplete
  [ "$rc" = 3 ] && die CGROUP_KILL_MISSING "cgroup.kill missing" incomplete
  return 0
}

reap_engine_status() {
  if [ -n "$ENGINE_PID" ]; then
    wait "$ENGINE_PID" 2>/dev/null
    ENGINE_EXIT=$?
  else
    ENGINE_EXIT=1
  fi
  return 0
}

finish() {
  FINISH_STATUS=$1  FINISH_CLEANUP=$2
  [ -n "$FINISHED" ] && return 0
  FINISHED=1
  if [ -n "$CGDIR" ] && [ -d "$CGDIR/engine" ]; then
    wait_engine_empty "$CGDIR/engine" || FINISH_CLEANUP=incomplete
  fi
  if [ -n "$FRAME_FIFO" ]; then
    (
      exec 8>&-
    ) 2>/dev/null
    rm -f "$FRAME_FIFO" 2>/dev/null
  fi
  if [ -n "$SUP_ORIGIN" ] && [ -d "$SUP_ORIGIN" ]; then
    printf '0\n' > "$SUP_ORIGIN/cgroup.procs" 2>/dev/null || FINISH_CLEANUP=incomplete
  fi
  release_lock
  if [ -n "$CGDIR" ]; then
    rm -f "$CGDIR/propagation-ok" "$CGDIR/stage2-error" 2>/dev/null
    rmdir_if_empty "$CGDIR/engine" || FINISH_CLEANUP=incomplete
    rmdir_if_empty "$CGDIR/sup" || FINISH_CLEANUP=incomplete
    rmdir_if_empty "$CGDIR" || FINISH_CLEANUP=incomplete
    if [ "$FINISH_CLEANUP" = incomplete ]; then
      printf '[dsh-su] 1 %s error CLEANUP cgroup\n' "$INSTANCE" >&2
    fi
  fi
  remove_state_path "$ENGINE_PID_FILE"
  remove_state_path "$FRAME_FIFO"
  remove_state_path "$STATEDIR/control-state"
  remove_state_path "$STATEDIR" || FINISH_CLEANUP=incomplete
  printf '[dsh-su] 1 %s exit %s %s\n' "$INSTANCE" "$FINISH_STATUS" "$FINISH_CLEANUP" >&2
  exit "$FINISH_STATUS"
}

main_run() {
  parse_args "$@"
  validate_args
  acquire_lock
  setup_cgroups
  emit hello
  FRAME_FIFO="$STATEDIR/engine-frame"
  mkfifo "$FRAME_FIFO" || die CLEANUP "frame fifo create failed"
  start_engine "$FRAME_FIFO" --cgdir "$CGDIR" --rootfs "$ROOTFS" --
  exec 8>"$FRAME_FIFO"
  relay_frame
  control_loop
  # wait: control EOF/stop or engine self-exit; poll both, 0.1s interval.
  while :; do
    [ -f "$STATEDIR/control-state" ] && break
    if ! kill -0 "$ENGINE_PID" 2>/dev/null; then break; fi
    sleep 0.1
  done
  [ -f "$STATEDIR/control-state" ] && STOP_KIND=$(cat "$STATEDIR/control-state")
  if [ "$STOP_KIND" = stop ]; then
    stop_engine
  fi
  emit phase stopping
  reap_engine_status
  finish "$ENGINE_EXIT" clean
}

if [ "${1:-}" = run ]; then
  shift
  main_run "$@"
fi

if [ "${1:-}" != run ] && [ "${1:-}" != stage2 ]; then
  printf '[dsh-su] 1 - error ARGUMENT usage\n' >&2
  exit 2
fi
