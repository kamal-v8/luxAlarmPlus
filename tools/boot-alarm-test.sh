#!/usr/bin/env bash
#
# tools/boot-alarm-test.sh
#
# End-to-end harness that proves luxAlarm+ registers its alarm with
# AlarmManager after a reboot in which the device is NEVER unlocked.
#
# Why a screen lock is required
# -----------------------------
# The alarm schedule lives in a Room DB in credential-encrypted (CE) storage.
# CE storage is only unlocked after the user enters their credential, so on a
# device without a lock screen the "locked" state never occurs and the
# direct-boot path is never exercised. The harness therefore installs a PIN
# (default 1234) before rebooting.
#
# The key assertion is "alarm_registered_while_locked": after `svc power
# reboot` and BEFORE any unlock input, `dumpsys alarm` must show a pending
# alarm for com.dsalmun.luxalarmplus. Before the direct-boot feature lands
# that assertion fails, which is exactly what this harness is meant to show.
#
# Usage
#   tools/boot-alarm-test.sh                 # full flow (seed -> reboot -> unlock)
#   tools/boot-alarm-test.sh --seed          # only seed an alarm + capture "before" state
#   tools/boot-alarm-test.sh --reboot        # only reboot + run the pre-unlock assertions
#   tools/boot-alarm-test.sh --unlock        # only unlock + run the post-unlock assertions
#   tools/boot-alarm-test.sh --cleanup       # remove the screen lock and uninstall the app
#   tools/boot-alarm-test.sh --wait          # only wait for the alarm to fire (device stays locked)
#   tools/boot-alarm-test.sh --help
#
# Environment overrides
#   ANDROID_SERIAL / --serial   target device (default: the only attached device)
#   LUXALARM_PIN                screen lock PIN (default: 1234)
#   LUXALARM_APK                path to the debug APK
#   LUXALARM_LOG_DIR            directory for the log files
#   LUXALARM_SEED_MINUTES       how far in the future the alarm is set (default: 10)
#   LUXALARM_SEED_MODE          "instrumentation" (default) or "ui"
#   LUXALARM_WAIT_FOR_ALARM     "1" waits for the alarm to fire while the device stays locked
#
set -euo pipefail

# ------------------------------------------------------------------ config --
SCRIPT_PATH="${BASH_SOURCE[0]}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$SCRIPT_PATH")" && pwd)"

# Bash reads the script file while it runs. When the file is modified in the
# meantime (by an editor, a formatter or another agent) the run dies with a
# syntax error in the middle of the flow. Therefore the work is done on a
# private copy of the script.
if [[ -z "${LUXALARM_REEXEC:-}" ]]; then
    _self_copy="$(mktemp "${TMPDIR:-/tmp}/luxalarm-boot-test.XXXXXX.sh")"
    cp "$SCRIPT_PATH" "$_self_copy"
    export LUXALARM_REEXEC=1
    export LUXALARM_SCRIPT_DIR="$SCRIPT_DIR"
    exec bash "$_self_copy" "$@"
fi
SCRIPT_DIR="${LUXALARM_SCRIPT_DIR:-$SCRIPT_DIR}"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

PKG="${LUXALARM_PKG:-com.dsalmun.luxalarmplus}"
MAIN_ACTIVITY="${LUXALARM_ACTIVITY:-com.dsalmun.luxalarmplus/.MainActivity}"
PIN="${LUXALARM_PIN:-1234}"
APK_PATH="${LUXALARM_APK:-$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
ADB="${ADB:-adb}"

# How many minutes in the future the seeded alarm is scheduled for. The
# Material3 time-picker dial only exposes 5-minute steps, so the value is
# rounded up to the next multiple of 5 (=> 10..14 minutes in the future).
SEED_MINUTES_AHEAD="${LUXALARM_SEED_MINUTES:-10}"

# How the alarm is seeded: through the SeedAlarmTest instrumentation helper
# (default) or by driving the Material3 time picker with uiautomator.
SEED_MODE="${LUXALARM_SEED_MODE:-instrumentation}"

# The androidTest APK: it carries the SeedAlarmTest helper.
TEST_APK_PATH="${LUXALARM_TEST_APK:-$PROJECT_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
TEST_PKG="${LUXALARM_TEST_PKG:-com.dsalmun.luxalarmplus.test}"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"

LOG_DIR="${LUXALARM_LOG_DIR:-$SCRIPT_DIR/logs/$(date +%Y%m%d-%H%M%S)}"
UI_XML="$LOG_DIR/ui-dump.xml"

# ----------------------------------------------------------------- output --
if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'
else
    C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BOLD=""
fi

step()  { printf '\n%s==> %s%s\n' "$C_BOLD" "$*" "$C_RESET"; }
info()  { printf '    %s\n' "$*"; }
warn()  { printf '    %s%s%s\n' "$C_YELLOW" "$*" "$C_RESET"; }
die()   { printf '\n%sFATAL: %s%s\n' "$C_RED" "$*" "$C_RESET" >&2; exit 2; }

# ------------------------------------------------------------- assertions --
declare -a RESULT_NAME=() RESULT_STATE=() RESULT_DETAIL=()

pass() { # $1 = name, $2 = detail
    RESULT_NAME+=("$1"); RESULT_STATE+=("PASS"); RESULT_DETAIL+=("$2")
    printf '    %sPASS%s %-32s %s\n' "$C_GREEN" "$C_RESET" "$1" "$2"
}
fail() { # $1 = name, $2 = detail
    RESULT_NAME+=("$1"); RESULT_STATE+=("FAIL"); RESULT_DETAIL+=("$2")
    printf '    %sFAIL%s %-32s %s\n' "$C_RED" "$C_RESET" "$1" "$2"
}
skip() { # $1 = name, $2 = detail
    RESULT_NAME+=("$1"); RESULT_STATE+=("SKIP"); RESULT_DETAIL+=("$2")
    printf '    %sSKIP%s %-32s %s\n' "$C_YELLOW" "$C_RESET" "$1" "$2"
}

print_summary() {
    local i total_pass=0 total_fail=0 total_skip=0 colour
    printf '\n%s================ SUMMARY ================%s\n' "$C_BOLD" "$C_RESET"
    for i in "${!RESULT_NAME[@]}"; do
        case "${RESULT_STATE[$i]}" in
            PASS) total_pass=$((total_pass + 1)); colour="$C_GREEN" ;;
            FAIL) total_fail=$((total_fail + 1)); colour="$C_RED" ;;
            *)    total_skip=$((total_skip + 1)); colour="$C_YELLOW" ;;
        esac
        printf '%s%-4s%s %-32s %s\n' "$colour" "${RESULT_STATE[$i]}" "$C_RESET" \
            "${RESULT_NAME[$i]}" "${RESULT_DETAIL[$i]}"
    done
    printf '%s--------------------------------------------%s\n' "$C_BOLD" "$C_RESET"
    printf '    %d passed, %d failed, %d skipped\n' "$total_pass" "$total_fail" "$total_skip"
    printf '    log files   : %s\n' "$LOG_DIR"
    printf '    adb logcat  : %s\n' "$LOG_DIR/logcat-post-reboot.txt"
    printf '    alarm dumps : %s\n' "$LOG_DIR/dumpsys-alarm-*.txt"
    if (( total_fail > 0 )); then
        printf '\n%sRESULT: FAIL%s\n' "$C_RED" "$C_RESET"
        return 1
    fi
    printf '\n%sRESULT: PASS%s\n' "$C_GREEN" "$C_RESET"
    return 0
}

# -------------------------------------------------------------- adb helpers --
adb_cmd() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        "$ADB" -s "$ANDROID_SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

# Strip CR/LF from adb output.
trim() { tr -d '\r\n' ; }

device_id() {
    local out
    out=$(adb_cmd shell getprop ro.serialno 2>/dev/null | trim || true)
    [[ -n "$out" ]] && printf '%s' "$out" || printf 'unknown-device'
}

# Fail loudly (and early) when the emulator died or is still booting.
# The emulator occasionally drops adbd for a few seconds, so wait for it to
# come back instead of failing the whole run.
require_device() {
    local state i
    for i in $(seq 1 40); do
        state=$(adb_cmd get-state 2>/dev/null | trim || true)
        if [[ "$state" == "device" ]]; then
            return 0
        fi
        sleep 2
    done
    die "the device is not online (adb state: ${state:-none}) - is the emulator running?"
}

# ---------------------------------------------------------- UI automation --
# uiautomator dumps the hierarchy to the device and pulls it back. /data/local
# tmp is used because /sdcard is not readable while the device is locked.
# The dump is retried: uiautomator fails with "could not get idle state"
# while an animation is running.
ui_dump() { # $1 = optional tag, saves an extra copy as ui-<tag>.xml
    local tag="${1:-}" i
    require_device
    for i in 1 2 3 4 5; do
        if adb_cmd shell uiautomator dump /data/local/tmp/luxalarm-ui.xml >/dev/null 2>&1; then
            if [[ -n "$tag" ]]; then
                adb_cmd pull /data/local/tmp/luxalarm-ui.xml "$LOG_DIR/ui-$tag.xml" >/dev/null 2>&1 || return 1
                cp "$LOG_DIR/ui-$tag.xml" "$UI_XML"
            else
                adb_cmd pull /data/local/tmp/luxalarm-ui.xml "$UI_XML" >/dev/null 2>&1 || return 1
            fi
            [[ -s "$UI_XML" ]] && return 0
        fi
        sleep 1
    done
    return 1
}

ui_nodes() { tr '<' '\n' < "$UI_XML"; }

# Set LUXALARM_DEBUG=1 to trace every UI interaction. The output goes to
# stderr on purpose: stdout of ui_find_center is captured by the caller.
debug() { [[ "${LUXALARM_DEBUG:-0}" == "1" ]] && printf '    [dbg] %s\n' "$*" >&2 || true; }

# Builds the ERE that matches one uiautomator node.
#   $1 = attr (content-desc | text | resource-id)
#   $2 = pattern
#   $3 = mode: "regex"    - the pattern has to match the whole value
#            "contains" - the pattern is matched anywhere inside the value
#   $4 = optional class filter, e.g. "android.view.View"
# The uiautomator attribute order is index, text, resource-id, class, package,
# content-desc, ... so the class filter has to be placed accordingly.
node_re() {
    local attr="$1" pat="$2" mode="$3" cls="${4:-}" value_re
    case "$mode" in
        regex)    value_re="(${pat})" ;;
        contains) value_re="[^\"]*${pat}[^\"]*" ;;
        *) die "unknown node mode: $mode" ;;
    esac
    if [[ -z "$cls" ]]; then
        printf '%s="%s"' "$attr" "$value_re"
    elif [[ "$attr" == "text" ]]; then
        printf 'text="%s"[^>]*class="%s"' "$value_re" "$cls"
    else
        printf 'class="%s"[^>]*%s="%s"' "$cls" "$attr" "$value_re"
    fi
}

# ui_find_center <attr> <pattern> [mode] [class]
#   attr   : content-desc | text | resource-id
#   pattern: the value to look for
#   mode   : "regex" (default, the pattern must match the whole value) or
#            "contains" (substring match)
#   class  : optional class filter, e.g. "android.view.View". Needed to tell
#            the Material3 time-picker dial entries (View) from the header
#            text that repeats the same value (TextView).
# Prints "<x> <y>" (the centre of the node) on stdout, returns 1 if not found.
ui_find_center() {
    local attr="$1" pat="$2" mode="${3:-regex}" cls="${4:-}" line bounds inner
    local x1 y1 x2 y2 re
    re=$(node_re "$attr" "$pat" "$mode" "$cls")
    line=$(ui_nodes | grep -m1 -E "$re" || true)
    [[ -n "$line" ]] || return 1
    debug "match $attr=$pat mode=$mode class=$cls -> ${line:0:160}"
    bounds=$(printf '%s\n' "$line" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -n1 || true)
    [[ -n "$bounds" ]] || return 1
    inner=${bounds#*bounds=\"}; inner=${inner%\"}
    # "[933,2190][996,2253]" -> " 933 2190  996 2253 "
    inner=${inner//[/ }; inner=${inner//]/ }; inner=${inner//,/ }
    read -r x1 y1 x2 y2 <<< "$inner"
    [[ -n "$x1" && -n "$y1" && -n "$x2" && -n "$y2" ]] || return 1
    printf '%s %s\n' "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

# ui_tap <attr> <pattern> [mode] [class] -> 0 on success
ui_tap() {
    local attr="$1" pat="$2" mode="${3:-regex}" cls="${4:-}" xy
    xy=$(ui_find_center "$attr" "$pat" "$mode" "$cls") || return 1
    debug "tap $attr=$pat -> $xy"
    # shellcheck disable=SC2086
    adb_cmd shell input tap $xy
}

# Same as ui_tap but re-dumps the hierarchy between the attempts, because the
# Compose UI may not have rendered the node yet.
ui_tap_retry() { # $1 = attr, $2 = pattern, $3 = mode, $4 = class, $5 = attempts
    local attr="$1" pat="$2" mode="${3:-regex}" cls="${4:-}" attempts="${5:-5}" i
    for (( i = 1; i <= attempts; i++ )); do
        dismiss_intruders
        if ui_dump && ui_tap "$attr" "$pat" "$mode" "$cls"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# The emulator shows unrelated system permission dialogs (e.g. the Play Store
# asking for the camera). They cover the app, so get rid of them. Our own
# permission requests are accepted, everything else is denied.
dismiss_intruders() {
    # Anything that is not our app may be covering it: runtime permission
    # dialogs, first-run dialogs of the Google apps that ship with the
    # emulator image, the launcher, ...
    local i msg
    for i in 1 2 3 4 5 6; do
        ui_dump || return 0

        # A runtime permission dialog: accept ours, deny the foreign ones.
        if ui_has resource-id "permission_deny_button" contains; then
            msg=$(ui_text_of resource-id "permission_message" contains || true)
            if [[ "$msg" == *luxAlarm* ]]; then
                info "accepting the permission dialog for $PKG"
                ui_tap resource-id "permission_allow_button" contains || true
            else
                info "dismissing a foreign permission dialog (${msg:-unknown})"
                ui_tap resource-id "permission_deny_button" contains || true
            fi
            sleep 1
            continue
        fi

        # Our app is on top, nothing to dismiss.
        if ui_has package "$PKG" contains; then
            return 0
        fi

        # A foreign screen: use its own "leave" button if it has one.
        if ui_has text "Skip" regex; then
            info "dismissing a foreign dialog (Skip)"
            ui_tap text "Skip" regex || true
        elif ui_has text "Not now" regex; then
            info "dismissing a foreign dialog (Not now)"
            ui_tap text "Not now" regex || true
        elif ui_has text "Close" regex; then
            info "dismissing a foreign dialog (Close)"
            ui_tap text "Close" regex || true
        elif ui_has text "Cancel" regex; then
            info "dismissing a foreign dialog (Cancel)"
            ui_tap text "Cancel" regex || true
        elif ui_has text "OK" regex; then
            info "dismissing a foreign dialog (OK)"
            ui_tap text "OK" regex || true
        else
            info "a foreign screen is in the foreground, going back"
            adb_cmd shell input keyevent 4 >/dev/null 2>&1 || true
        fi
        sleep 1
    done
    return 0
}

ui_has() { # $1 = attr, $2 = pattern, $3 = mode, $4 = class
    ui_find_center "$1" "$2" "${3:-regex}" "${4:-}" >/dev/null 2>&1
}

ui_text_of() { # $1 = attr, $2 = pattern, $3 = mode, $4 = class
    local attr="$1" pat="$2" mode="${3:-regex}" cls="${4:-}" line re
    re=$(node_re "$attr" "$pat" "$mode" "$cls")
    line=$(ui_nodes | grep -m1 -E "$re" || true)
    [[ -n "$line" ]] || return 1
    printf '%s' "$line" | grep -oE "${attr}=\"[^\"]*\"" | head -n1 | sed -E "s/${attr}=\"//; s/\"$//"
}

# ------------------------------------------------------- device state info --
user_state() { # prints e.g. RUNNING_LOCKED / RUNNING_UNLOCKED / UNKNOWN
    local out
    out=$(adb_cmd shell dumpsys user 2>/dev/null | grep -m1 -E '^\s+State: ' || true)
    out=${out##*State: }
    out=$(printf '%s' "$out" | trim)
    [[ -n "$out" ]] || out="UNKNOWN"
    printf '%s' "$out"
}

user_is_locked()   { [[ "$(user_state)" == "RUNNING_LOCKED" ]]; }
user_is_unlocked() { [[ "$(user_state)" == "RUNNING_UNLOCKED" ]]; }

# Force the device into the locked state.
#
# Setting a PIN while the device is unlocked does NOT lock it - the keyguard
# only requires the PIN the next time the screen turns off. Without an actual
# lock the next boot comes back RUNNING_UNLOCKED, the direct-boot path is
# never entered and the test proves nothing. Sleeping the screen with a secure
# keyguard configured locks the device for real.
lock_device() {
    require_device
    if ! user_is_unlocked; then
        info "the device is already locked"
        return 0
    fi
    # Setting a PIN locks the device asynchronously, and the emulator sometimes
    # keeps the keyguard in "swipe" mode until the screen has been cycled once.
    # Try a few times: sleep the screen, wake it, check.
    local i
    for i in 1 2 3; do
        adb_cmd shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
        sleep 2
        adb_cmd shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        sleep 2
        if ! user_is_unlocked; then
            info "the device is locked"
            return 0
        fi
    done
    return 1
}

# Extract the "N pending alarms:" block of a `dumpsys alarm` capture.
pending_alarms_section() { # $1 = file
    awk '
        /pending alarms:/ { inside = 1 }
        inside { print }
        inside && /LazyAlarmStore stats:/ { exit }
    ' "$1"
}

# Extract the "Next alarm clock information:" block of a `dumpsys alarm` capture.
alarm_clock_section() { # $1 = file
    awk '
        /Next alarm clock information:/ { inside = 1 }
        inside { print }
        inside && /pending alarms:/ { exit }
    ' "$1"
}

# The package name of a pending alarm appears at the end of the Alarm{...}
# line, e.g. "RTC #7: Alarm{9c2a9db type 1 ... com.dsalmun.luxalarmplus}".
package_regex() { printf '%s' "$PKG" | sed 's/\./\\./g'; }

alarm_lines_for_pkg() { # $1 = dumpsys alarm file
    pending_alarms_section "$1" | grep -E "Alarm\{.*$(package_regex)\}" || true
}

alarm_trigger_times() { # $1 = dumpsys alarm file
    pending_alarms_section "$1" \
        | grep -E "Alarm\{.*$(package_regex)\}" \
        | grep -oE 'origWhen=[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]+' \
        | sed 's/origWhen=//' || true
}

capture_alarm_dump() { # $1 = label -> prints the file name
    local label="$1" out="$LOG_DIR/dumpsys-alarm-$1.txt" i
    # The emulator drops adbd now and then, retry until the dump has content.
    for i in 1 2 3 4 5; do
        adb_cmd shell dumpsys alarm > "$out" 2>/dev/null || true
        [[ -s "$out" ]] && break
        sleep 2
    done
    if [[ -s "$out" ]]; then
        {
            printf '### Next alarm clock information ###\n'
            alarm_clock_section "$out"
            printf '\n### Pending alarms for %s ###\n' "$PKG"
            alarm_lines_for_pkg "$out"
            printf '\n### All pending alarms ###\n'
            pending_alarms_section "$out"
        } > "$LOG_DIR/dumpsys-alarm-$1-excerpt.txt"
    fi
    printf '%s' "$out"
}

# --------------------------------------------------------------- app state --
app_installed() { adb_cmd shell pm path "$PKG" 2>/dev/null | grep -q "package:"; }

app_force_stop() { adb_cmd shell am force-stop "$PKG" >/dev/null 2>&1 || true; }

# The app can disappear underneath us (a concurrent build/uninstall on the
# same emulator), so make sure it is there before driving the UI.
ensure_app_installed() {
    if app_installed; then return 0; fi
    warn "$PKG is not installed - installing it again"
    adb_cmd install -r -t "$APK_PATH" >"$LOG_DIR/install.log" 2>&1 || return 1
    app_installed
}

# Keep the screen on. Without this the emulator dozes, the keyguard takes
# over the screen and uiautomator dumps the launcher instead of the app.
wake_screen() {
    adb_cmd shell svc power stayon true >/dev/null 2>&1 || true
    adb_cmd shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    sleep 1
}

app_launch() {
    # Retry until the app is really in the foreground.
    local i
    for i in 1 2 3 4 5; do
        ensure_app_installed || return 1
        wake_screen
        adb_cmd shell am start -W -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
        sleep 3
        dismiss_intruders
        if ui_dump && ui_has package "$PKG" contains; then
            return 0
        fi
        info "the app did not come to the foreground (attempt $i), retrying"
        sleep 1
    done
    return 1
}

# ============================================================== stage: seed ==
# Seeds exactly one alarm, a few minutes in the future.
#
# The default route is the SeedAlarmTest instrumentation helper: it runs
# inside the app process, inserts the alarm into the Room database, registers
# it with the AlarmManager and refreshes the device-protected mirror - exactly
# what the app does when the user adds an alarm through the UI.
#
# The UI route (Material3 time picker) is kept as a fallback, but it is
# unreliable: the Compose dialog window is not part of the accessibility
# dump, so uiautomator cannot see the dial.
seed_alarm() {
    case "$SEED_MODE" in
        instrumentation) seed_alarm_via_instrumentation ;;
        ui)              seed_alarm_via_ui ;;
        *) die "unknown LUXALARM_SEED_MODE: $SEED_MODE" ;;
    esac
}

# Installs the androidTest APK (it carries the SeedAlarmTest helper).
ensure_test_apk_installed() {
    # The test APK has to match the app build, otherwise the instrumentation
    # crashes with a stale class/method.
    if [[ ! -f "$TEST_APK_PATH" || ( -f "$APK_PATH" && "$APK_PATH" -nt "$TEST_APK_PATH" ) ]]; then
        info "the test APK is missing or older than the app APK, building it"
        if ! (cd "$PROJECT_ROOT" && ./gradlew :app:assembleDebugAndroidTest --console=plain) \
                >"$LOG_DIR/build-androidTest.log" 2>&1; then
            warn "could not build the test APK, see $LOG_DIR/build-androidTest.log"
            [[ -f "$TEST_APK_PATH" ]] || return 1
        fi
    fi
    if ! adb_cmd install -r -t "$TEST_APK_PATH" >"$LOG_DIR/install-androidTest.log" 2>&1; then
        warn "could not install the test APK, see $LOG_DIR/install-androidTest.log"
        return 1
    fi
    return 0
}

# Seeds the alarm through the SeedAlarmTest instrumentation.
seed_alarm_via_instrumentation() {
    require_device
    local out seed_lines trigger

    step "Seeding an alarm $SEED_MINUTES_AHEAD minutes in the future (instrumentation)"

    ensure_test_apk_installed || die "the test APK could not be installed"

    # The test logs its result with the tag SeedAlarmTest, the logcat is
    # cleared first so that only this run is read back.
    adb_cmd logcat -c >/dev/null 2>&1 || true

    out=$(adb_cmd shell am instrument -w -r \
              -e minutesAhead "$SEED_MINUTES_AHEAD" \
              -e class com.dsalmun.luxalarmplus.SeedAlarmTest \
              "$TEST_PKG/$TEST_RUNNER" 2>&1 | tr -d '\r')
    printf '%s\n' "$out" > "$LOG_DIR/seed-instrumentation.log"

    if ! printf '%s\n' "$out" | grep -qE "^OK \("; then
        printf '%s\n' "$out" | tail -30 | sed 's/^/    /'
        die "the seeding instrumentation failed, see $LOG_DIR/seed-instrumentation.log"
    fi

    adb_cmd logcat -d -s SeedAlarmTest:* > "$LOG_DIR/seed-logcat.txt" 2>/dev/null || true
    seed_lines=$(grep -oE "SEED .*" "$LOG_DIR/seed-logcat.txt" 2>/dev/null || true)
    if [[ -z "$seed_lines" ]]; then
        die "the seeding instrumentation did not log anything, see $LOG_DIR/seed-logcat.txt"
    fi
    printf '%s\n' "$seed_lines" | sed 's/^/    /'
    if ! printf '%s\n' "$seed_lines" | grep -q "SEED active=\[AlarmItem"; then
        die "the seeding instrumentation did not report an active alarm"
    fi

    # The trigger time is needed to compare with the AlarmManager dump.
    trigger=$(printf '%s\n' "$seed_lines" | grep -oE "SEED nextTrigger=[0-9]+" | head -n1 | cut -d= -f2 || true)
    if [[ -n "$trigger" ]]; then
        # The trigger is in milliseconds; the file name says so as well.
        printf '%s\n' "$trigger" > "$LOG_DIR/seeded-trigger-epoch-ms.txt"
        printf '%s\n' "$trigger" > "$LOG_DIR/seeded-trigger-epoch.txt"
        # The instrumentation reports milliseconds, 'date -d @' wants seconds.
        info "seeded alarm triggers at epoch $trigger ($(adb_cmd shell "date -d @$((trigger / 1000)) +%H:%M:%S" | trim))"
    fi
}

# Seeds the alarm by driving the Material3 time picker.
seed_alarm_via_ui() {
    require_device
    local target_hm target_h target_m target_h_num target_m_num
    target_hm=$(compute_target_time)
    printf '%s\n' "$target_hm" > "$LOG_DIR/target-time.txt"
    target_h=${target_hm%%:*}
    target_m=${target_hm##*:}
    # The dial labels are not zero padded ("0 hours", "5 minutes").
    target_h_num=$((10#$target_h))
    target_m_num=$((10#$target_m))

    step "Seeding an alarm at $target_hm (device time)"

    app_launch || die "could not launch $MAIN_ACTIVITY"
    dismiss_intruders
    ui_dump seed-1-main || die "uiautomator dump failed (main screen)"

    # The FAB is exposed with the content description "Add Alarm".
    ui_tap_retry content-desc "Add Alarm" contains || die "could not find the 'Add Alarm' button"

    sleep 1
    ui_dump seed-2-picker || die "uiautomator dump failed (time picker)"
    dismiss_intruders

    # The Material3 dial lists the hours 0..23 as content-desc "N hours" and
    # the minutes in 5-minute steps as "N minutes". Both are android.view.View
    # nodes; the header repeats the current value as a TextView, so the class
    # filter is what tells the dial entries apart from the header.
    local i
    for i in 1 2 3; do
        if ui_has content-desc "[0-9]+ minutes" regex "android.view.View"; then
            break
        fi
        ui_tap_retry content-desc "${target_h_num} hours" regex "android.view.View" \
            || die "could not select hour $target_h"
        sleep 1
        ui_dump "seed-3-hour-$i" || die "uiautomator dump failed (minute dial)"
    done
    if ! ui_has content-desc "[0-9]+ minutes" regex "android.view.View"; then
        die "the time picker did not switch to the minute dial"
    fi

    ui_tap_retry content-desc "${target_m_num} minutes" regex "android.view.View" \
        || die "could not select minute $target_m"
    ui_dump seed-4-minute || die "uiautomator dump failed (minute dial)"

    sleep 1
    ui_dump seed-5-set || die "uiautomator dump failed (set button)"
    ui_tap_retry text "Set" regex || die "could not find the 'Set' button"

    sleep 2

    # Verify that the alarm really landed in the list.
    ui_dump seed-6-list || die "uiautomator dump failed (alarm list)"
    if ui_has text "$target_hm" regex; then
        info "alarm list shows $target_hm"
    else
        die "the alarm list does not show $target_hm - seeding failed"
    fi
}

# Device-side time arithmetic so the target time always matches the device
# clock and the device time zone.
compute_target_time() {
    local epoch target
    epoch=$(adb_cmd shell "echo \$(( \$(date +%s) + $((SEED_MINUTES_AHEAD * 60)) ))" | trim)
    target=$(( (epoch + 299) / 300 * 300 ))
    adb_cmd shell "date -d @$target +%H:%M" | trim
}

# ========================================================== stage: install ==
stage_install() {
    require_device
    step "Installing the debug APK"
    if [[ ! -f "$APK_PATH" ]]; then
        die "APK not found at $APK_PATH - run ./gradlew :app:assembleDebug first"
    fi
    info "apk: $APK_PATH"
    if ! adb_cmd install -r -t "$APK_PATH" >"$LOG_DIR/install.log" 2>&1; then
        warn "install failed, see $LOG_DIR/install.log"
        if app_installed; then
            warn "the app is still installed, continuing with the installed build"
        else
            die "could not install $APK_PATH"
        fi
    else
        info "installed"
    fi
    if ! app_installed; then
        die "$PKG is not installed on the device"
    fi
    pass "apk_installed" "$(basename "$APK_PATH")"
}

# ============================================================ stage: lock ===
stage_lock() {
    require_device
    step "Ensuring a screen lock (PIN $PIN)"
    # "Pin already set" is fine, the credential is verified right after.
    local out
    out=$(adb_cmd shell locksettings set-pin "$PIN" 2>&1 | trim || true)
    if adb_cmd shell locksettings verify --old "$PIN" >/dev/null 2>&1; then
        info "locksettings set-pin: ${out:-<no output>}"
        info "PIN $PIN is active"
        pass "screen_lock_set" "PIN $PIN active"
        return 0
    fi
    # Another credential is set: try to replace it with $PIN.
    out=$(adb_cmd shell locksettings set-pin --old "$PIN" "$PIN" 2>&1 | trim || true)
    if adb_cmd shell locksettings verify --old "$PIN" >/dev/null 2>&1; then
        info "PIN $PIN is active"
        pass "screen_lock_set" "PIN $PIN active"
        return 0
    fi
    die "the PIN $PIN could not be set on the device: ${out:-unknown error}"
}

# =========================================================== stage: before ==
stage_before() {
    require_device
    step "Capturing the 'before reboot' alarm state"
    local out
    out=$(capture_alarm_dump before)
    info "saved: $out"
    if [[ -s "$LOG_DIR/dumpsys-alarm-before-excerpt.txt" ]]; then
        grep -A4 -i -E "$PKG" "$LOG_DIR/dumpsys-alarm-before-excerpt.txt" | head -20 | sed 's/^/    /' || true
    fi
    # The registration is asynchronous, give it a moment.
    local i lines
    for i in $(seq 1 10); do
        lines=$(alarm_lines_for_pkg "$out")
        [[ -n "$lines" ]] && break
        sleep 2
        out=$(capture_alarm_dump before)
    done

    if [[ -n "$(alarm_lines_for_pkg "$out")" ]]; then
        pass "alarm_registered_before" "$(alarm_lines_for_pkg "$out" | head -n1 | sed -E 's/^ *//')"
    elif grep -q "Reason=data_cleared" "$out" 2>/dev/null; then
        fail "alarm_registered_before" "the app data was cleared after the seeding (a concurrent Gradle/connectedAndroidTest run?) - the alarm was cancelled"
    elif grep -q "$(package_regex)" "$out" 2>/dev/null; then
        fail "alarm_registered_before" "the alarm of $PKG was cancelled before the reboot"
    else
        fail "alarm_registered_before" "no pending alarm for $PKG before the reboot"
    fi
}

# =========================================================== stage: reboot ==
stage_reboot() {
    require_device
    step "Rebooting the device (no unlock!)"
    # 'svc power reboot' is the documented way, but on some emulator images it
    # fails with "Attempt to invoke interface method ... on a null object
    # reference" (the shell cannot reach IPowerManager). 'adb reboot' does the
    # same thing and works there, so fall back to it.
    adb_cmd shell svc power reboot >/dev/null 2>&1 || true
    sleep 3
    if adb_cmd get-state 2>/dev/null | grep -q '^device$'; then
        info "'svc power reboot' did not take effect, using 'adb reboot'"
        adb_cmd reboot >/dev/null 2>&1 || die "the reboot command failed"
    fi

    # Wait for the device to actually go away, otherwise wait-for-device can
    # return while the old (still running) instance is still listed.
    local i
    for i in $(seq 1 60); do
        if ! adb_cmd get-state 2>/dev/null | grep -q '^device$'; then break; fi
        sleep 1
    done
    adb_cmd wait-for-device || die "the device did not come back after the reboot"

    # wait-for-device only means adbd is up; the system is not booted yet.
    for i in $(seq 1 180); do
        if [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | trim)" == "1" ]]; then break; fi
        sleep 2
    done
    if [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | trim)" != "1" ]]; then
        die "the device did not finish booting (sys.boot_completed != 1)"
    fi
    info "boot completed"

    # Give the package manager and the boot broadcast a moment.
    for i in $(seq 1 60); do
        if app_installed; then break; fi
        sleep 2
    done
    sleep 3

    # The user state is restored asynchronously: right after the boot the
    # system can still report RUNNING_UNLOCKED (the user was unlocked when
    # the device went down) until the credential-encrypted storage is locked
    # again. Wait for that, otherwise the assertion below is a race.
    for i in $(seq 1 30); do
        if user_is_locked; then break; fi
        sleep 2
    done
}

# The log lines the app writes while it boots (pre-unlock). The wording may
# change, so the keywords are matched loosely - but only on lines that really
# belong to the app (a system line that merely mentions "bootreceiver" must
# not count).
app_boot_log_lines() {
    local file="$LOG_DIR/logcat-post-reboot.txt"
    [[ -s "$file" ]] || return 0
    # Only the lines the app itself logged: "<date> <time> <pid> <tid> <lvl> <Tag>: ...".
    # System lines that merely mention the receiver (for example the
    # ActivityManager broadcast log) must not count.
    grep -E "^[0-9]{2}-[0-9]{2} [0-9:.]+ +[0-9]+ +[0-9]+ [VDIWE] +(BootReceiver|AlarmRepository|DeviceProtectedStore|SeedAlarmTest):" \
        "$file" 2>/dev/null \
        | grep -iE "boot|reschedul|device.protect|direct.?boot|schedul" || true
}

# ================================================== stage: pre-unlock checks ==
stage_preunlock_checks() {
    require_device
    step "Asserting the direct-boot state (the user is still LOCKED)"

    adb_cmd shell dumpsys user 2>/dev/null | grep -E "State:|Unlock time|Started users state" | sed 's/^/    /' || true

    if user_is_locked; then
        pass "user_locked_after_reboot" "State: $(user_state)"
    else
        fail "user_locked_after_reboot" "expected RUNNING_LOCKED, got $(user_state)"
    fi

    # The BOOT_COMPLETED broadcast and the boot receiver are slow on this
    # emulator image (software rendering): wait for the app to show up in the
    # log and for the alarm to show up in the AlarmManager before deciding
    # that the feature is broken.
    step "Waiting for the boot receiver (still locked)"
    local i dump lines triggers hits

    dump=$(capture_alarm_dump locked)
    adb_cmd logcat -d > "$LOG_DIR/logcat-post-reboot.txt" 2>/dev/null || true
    for i in $(seq 1 60); do
        lines=$(alarm_lines_for_pkg "$dump")
        hits=$(app_boot_log_lines)
        # Both have to be there: the receiver logs that it did its work AND
        # the alarm has to be visible in the AlarmManager.
        if [[ -n "$lines" && -n "$hits" ]]; then
            break
        fi
        sleep 4
        dump=$(capture_alarm_dump locked)
        adb_cmd logcat -d > "$LOG_DIR/logcat-post-reboot.txt" 2>/dev/null || true
    done

    step "Checking that the app ran before the unlock (logcat)"
    adb_cmd logcat -d > "$LOG_DIR/logcat-post-reboot.txt" 2>/dev/null || true
    adb_cmd logcat -d -s BootReceiver:* AlarmRepository:* DeviceProtectedStore:* > "$LOG_DIR/logcat-tags-post-reboot.txt" 2>/dev/null || true
    info "saved: $LOG_DIR/logcat-post-reboot.txt"

    # The wording of the boot log line may change, so match loosely on
    # boot / reschedul / device-protected / direct boot - but only on lines
    # that really come from the app.
    hits=$(app_boot_log_lines)
    if [[ -n "$hits" ]]; then
        printf '%s\n' "$hits" | head -n 8 | sed 's/^/    /'
        pass "app_ran_pre_unlock" "$(printf '%s\n' "$hits" | wc -l | tr -d ' ') matching log line(s)"
    else
        fail "app_ran_pre_unlock" "no boot/reschedule log lines from the app"
    fi

    if printf '%s\n' "$hits" | grep -qiE "boot detected|BOOT_COMPLETED|boot_completed"; then
        pass "boot_receiver_ran" "the BootReceiver handled the boot broadcast"
    else
        fail "boot_receiver_ran" "no boot broadcast handling in the app log"
    fi

    step "Checking that the alarm is registered with AlarmManager while LOCKED"
    info "saved: $dump"

    lines=$(alarm_lines_for_pkg "$dump")
    triggers=$(alarm_trigger_times "$dump")
    if [[ -n "$lines" ]]; then
        printf '%s\n' "$lines" | sed 's/^/    /'
        [[ -n "$triggers" ]] && printf '    trigger time(s): %s\n' "$(printf '%s' "$triggers" | tr '\n' ' ')"
        pass "alarm_registered_while_locked" "pending alarm present for $PKG"
    else
        printf '    (no pending alarm for %s while the device is locked)\n' "$PKG"
        fail "alarm_registered_while_locked" "no pending alarm for $PKG while the user is locked"
    fi
}

# ================================================== stage: wait for the ring ==
# Waits until the seeded alarm fires - the device stays locked the whole time
# - and captures the evidence that the alarm rang over the keyguard:
# the CATEGORY_ALARM notification, the alarm screen on top and a screenshot.
stage_wait_for_alarm() {
    require_device
    step "Waiting for the alarm to fire (the device stays locked)"

    local trigger now wait_s
    trigger=$(cat "$LOG_DIR/seeded-trigger-epoch.txt" 2>/dev/null || true)
    if [[ -z "$trigger" ]]; then
        fail "alarm_rang_while_locked" "the trigger time of the seeded alarm is unknown"
        return 1
    fi
    now=$(adb_cmd shell date +%s | trim)
    if [[ -z "$now" || "$now" -lt 1000000000 ]]; then
        warn "could not read the device clock, falling back to the host clock"
        now=$(( $(date +%s) ))
    fi
    wait_s=$(( trigger / 1000 - now + 5 ))
    info "the alarm rings at epoch $trigger, waiting ${wait_s}s (the user stays locked)"

    # Wait in small steps so the device state can be watched and the harness
    # reacts to a device that goes away.
    local waited=0
    while (( waited < wait_s )); do
        sleep 5
        waited=$(( waited + 5 ))
        if (( waited % 30 == 0 )); then
            info "still locked: $(user_state), $(( wait_s - waited ))s to go"
        fi
    done

    sleep 10

    # The notification has to be posted.
    adb_cmd shell dumpsys notification --noredact > "$LOG_DIR/dumpsys-notification-ring.txt" 2>/dev/null || true
    if grep -q "$PKG" "$LOG_DIR/dumpsys-notification-ring.txt" 2>/dev/null &&
        grep -qE "category=alarm|android\.category\.ALARM" "$LOG_DIR/dumpsys-notification-ring.txt" 2>/dev/null; then
        grep -E "NotificationRecord.*$PKG" "$LOG_DIR/dumpsys-notification-ring.txt" |
            head -3 | cut -c1-220 | sed 's/^/    /' || true
        pass "alarm_notification_posted" "a CATEGORY_ALARM notification is posted while locked"
    else
        fail "alarm_notification_posted" "no CATEGORY_ALARM notification for $PKG"
    fi

    # The alarm screen has to be on top of the keyguard.
    local focus
    focus=$(adb_cmd shell dumpsys window 2>/dev/null | grep -m1 -E "mCurrentFocus" | trim || true)
    info "focus: $focus"
    if [[ "$focus" == *"$PKG"* ]]; then
        pass "alarm_screen_on_top" "$focus"
    else
        fail "alarm_screen_on_top" "the alarm screen is not on top (focus: ${focus:-unknown})"
    fi

    # A screenshot of the keyguard with the alarm on top of it. The screen
    # buffer is streamed over adb: /sdcard is not writable while the device
    # is locked.
    if adb_cmd exec-out screencap -p > "$LOG_DIR/alarm-over-keyguard.png" 2>/dev/null &&
        [[ -s "$LOG_DIR/alarm-over-keyguard.png" ]]; then
        pass "alarm_screenshot" "$LOG_DIR/alarm-over-keyguard.png"
    else
        fail "alarm_screenshot" "the screenshot could not be taken"
    fi

    # The receiver has to have run.
    adb_cmd logcat -d > "$LOG_DIR/logcat-at-alarm-time.txt" 2>/dev/null || true
    if grep -qE "AlarmReceiver|AlarmActivity" "$LOG_DIR/logcat-at-alarm-time.txt" 2>/dev/null; then
        grep -E "AlarmReceiver|AlarmActivity" "$LOG_DIR/logcat-at-alarm-time.txt" |
            tail -10 | sed 's/^/    /' || true
        pass "alarm_receiver_ran" "the AlarmReceiver handled the alarm"
    else
        fail "alarm_receiver_ran" "no AlarmReceiver log line"
    fi
}

# ========================================================= stage: unlock ===
open_bouncer() {
    # keyevent 82 (MENU) opens the credential bouncer on most builds; the
    # swipe-up is the fallback. Right after a boot the keyguard sometimes
    # needs a moment, so try a few times.
    local attempt
    for attempt in 1 2 3; do
        wake_screen
        sleep 1
        adb_cmd shell input keyevent 82 >/dev/null 2>&1 || true
        sleep 2
        if ui_dump && ui_has resource-id "com.android.systemui:id/pinEntry" contains; then
            return 0
        fi
        adb_cmd shell input swipe 540 1800 540 600 300 >/dev/null 2>&1 || true
        sleep 2
        if ui_dump && ui_has resource-id "com.android.systemui:id/pinEntry" contains; then
            return 0
        fi
        sleep 2
    done
    return 1
}

enter_pin_via_keypad() {
    local i digit xy
    for (( i = 0; i < ${#PIN}; i++ )); do
        digit="${PIN:$i:1}"
        ui_dump || return 1
        if ! xy=$(ui_find_center resource-id "com.android.systemui:id/key${digit}" regex); then
            warn "the PIN bouncer has no keypad (looking for key${digit})"
            ui_nodes | grep -oE 'resource-id="[^"]*"' | sort -u | head -15 | sed 's/^/        /'
            return 1
        fi
        # shellcheck disable=SC2086
        adb_cmd shell input tap $xy
        sleep 0.5
    done
    ui_dump || return 1
    xy=$(ui_find_center resource-id "com.android.systemui:id/key_enter" regex) || return 1
    # shellcheck disable=SC2086
    adb_cmd shell input tap $xy
    sleep 2
    # The bouncer has to be gone, otherwise the PIN was not accepted.
    if ui_dump && ui_has resource-id "com.android.systemui:id/pinEntry" contains; then
        return 1
    fi
    return 0
}

# Remove anything that is already typed into the PIN field (the delete key of
# the keyguard bouncer), so the 'input text' fallback starts from scratch.
clear_pin_field() {
    local i xy
    ui_dump || return 1
    xy=$(ui_find_center resource-id "com.android.systemui:id/delete_button" regex) || return 1
    for i in 1 2 3 4 5 6 7 8; do
        # shellcheck disable=SC2086
        adb_cmd shell input tap $xy
        sleep 0.2
    done
}

# Types the PIN into the (focused) text field of the bouncer. Used when the
# bouncer shows the soft keyboard instead of the numeric keypad.
enter_pin_via_text() {
    ui_dump || return 1
    ui_tap resource-id "com.android.systemui:id/pinEntry" contains || true
    sleep 1
    adb_cmd shell input text "$PIN" || true
    sleep 1
    adb_cmd shell input keyevent 66 || true
    sleep 1
    # The enter key of the soft keyboard, if it is up.
    if ui_dump && ui_has content-desc "Enter" contains; then
        ui_tap content-desc "Enter" contains || true
        sleep 1
    fi
    # The bouncer has to be gone, otherwise the PIN was not accepted.
    if ui_dump && ui_has resource-id "com.android.systemui:id/pinEntry" contains; then
        return 1
    fi
    return 0
}

# Unlock the device. Returns 0 as soon as the user state is RUNNING_UNLOCKED.
unlock_device() {
    require_device
    if user_is_unlocked; then return 0; fi

    if open_bouncer; then
        info "PIN bouncer is up, entering $PIN"
        if enter_pin_via_keypad; then
            info "PIN entered"
        else
            warn "keypad entry failed, falling back to 'input text'"
            clear_pin_field || true
            if enter_pin_via_text; then
                info "PIN entered"
            else
                warn "the text entry failed as well"
            fi
        fi
    else
        warn "no PIN bouncer found, falling back to 'locksettings clear --old $PIN'"
    fi

    local i
    for i in $(seq 1 30); do
        if user_is_unlocked; then return 0; fi
        sleep 1
    done

    # Last resort: remove the credential, the keyguard then goes away.
    warn "still locked, clearing the screen lock (locksettings clear --old $PIN)"
    adb_cmd shell locksettings clear --old "$PIN" >/dev/null 2>&1 || true
    for i in $(seq 1 30); do
        if user_is_unlocked; then
            # The credential is gone now - put the PIN back so that the next
            # run still starts from a locked device.
            adb_cmd shell locksettings set-pin "$PIN" >/dev/null 2>&1 || true
            return 0
        fi
        sleep 1
    done
    return 1
}

stage_unlock() {
    require_device
    step "Unlocking the device"

    if user_is_unlocked; then
        info "the device is already unlocked"
    else
        unlock_device || true
    fi

    if user_is_unlocked; then
        pass "user_unlocked" "State: $(user_state)"
    else
        fail "user_unlocked" "the device is still $(user_state)"
    fi

    step "Checking the alarm state after the unlock"
    local dump i
    dump=$(capture_alarm_dump unlocked)
    # The app re-schedules when it gets the USER_UNLOCKED broadcast, so the
    # alarm can be briefly missing while it is replaced.
    for i in $(seq 1 15); do
        alarm_lines_for_pkg "$dump" | grep -q . && break
        sleep 2
        dump=$(capture_alarm_dump unlocked)
    done
    info "saved: $dump"
    if alarm_lines_for_pkg "$dump" | grep -q .; then
        alarm_lines_for_pkg "$dump" | head -n2 | sed 's/^/    /'
        pass "alarm_registered_after_unlock" "pending alarm still present for $PKG"
    elif (( DO_WAIT )); then
        # The alarm rang before the unlock, so the one-shot was deactivated
        # when the device was unlocked: no alarm has to be pending any more.
        # What matters is that the deactivation was applied to the database.
        local applied
        applied=$(adb_cmd logcat -d -s AlarmRepository:* 2>/dev/null |
            grep -E "pending deactivation" | tail -n1 || true)
        if [[ -n "$applied" ]]; then
            printf '%s\n' "$applied" | sed 's/^/    /'
            pass "one_shot_deactivated_after_unlock" "the alarm that rang while locked was deactivated"
        else
            fail "one_shot_deactivated_after_unlock" \
                "the deactivation of the alarm that rang while locked was not applied"
        fi
    else
        fail "alarm_registered_after_unlock" "no pending alarm for $PKG after the unlock"
    fi

    # The app re-registers when it is finally able to read the CE database.
    adb_cmd shell am start -W -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
    sleep 3
    adb_cmd logcat -d -s BootReceiver:* AlarmRepository:* DeviceProtectedStore:* > "$LOG_DIR/logcat-post-unlock.txt" 2>/dev/null || true
    info "saved: $LOG_DIR/logcat-post-unlock.txt"
}

# ========================================================= stage: cleanup ==
stage_cleanup() {
    require_device
    step "Cleaning up (remove the screen lock and uninstall the app)"
    adb_cmd shell svc power stayon false >/dev/null 2>&1 || true
    if adb_cmd shell locksettings clear --old "$PIN" >/dev/null 2>&1; then
        info "screen lock removed"
    else
        adb_cmd shell locksettings clear >/dev/null 2>&1 || warn "could not clear the screen lock"
    fi
    if adb_cmd uninstall "$PKG" >/dev/null 2>&1; then
        info "$PKG uninstalled"
    else
        warn "could not uninstall $PKG"
    fi
    pass "cleanup" "lock removed, app uninstalled"
}

# =================================================================== main ==
usage() {
    sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

DO_SEED=0; DO_REBOOT=0; DO_UNLOCK=0; DO_CLEANUP=0; DO_ALL=0; DO_WAIT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --seed)    DO_SEED=1 ;;
        --reboot)  DO_REBOOT=1 ;;
        --unlock)  DO_UNLOCK=1 ;;
        --cleanup) DO_CLEANUP=1 ;;
        --all)     DO_ALL=1 ;;
        --wait)    DO_WAIT=1 ;;
        --serial)  ANDROID_SERIAL="${2:?--serial needs a device serial}"; shift ;;
        --pin)     PIN="${2:?--pin needs a value}"; shift ;;
        --log-dir) LOG_DIR="${2:?--log-dir needs a path}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1 (try --help)" ;;
    esac
    shift
done

if [[ "${LUXALARM_WAIT_FOR_ALARM:-0}" == "1" ]]; then
    DO_WAIT=1
fi
if (( DO_ALL || ( !DO_SEED && !DO_REBOOT && !DO_UNLOCK && !DO_CLEANUP ) )); then
    DO_SEED=1; DO_REBOOT=1; DO_UNLOCK=1
fi

mkdir -p "$LOG_DIR"

# Everything printed from here on also lands in the run log.
exec > >(tee -a "$LOG_DIR/run.log") 2>&1

printf '%s===== luxAlarm+ direct-boot harness =====%s\n' "$C_BOLD" "$C_RESET"
printf '    apk        : %s\n' "$APK_PATH"
printf '    package    : %s\n' "$PKG"
printf '    pin        : %s\n' "$PIN"
printf '    logs       : %s\n' "$LOG_DIR"

step "Waiting for the device"
adb_cmd wait-for-device || die "no device available"
for i in $(seq 1 60); do
    if [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | trim)" == "1" ]]; then break; fi
    sleep 2
done
info "device: $(device_id)"
pass "device_online" "$(device_id)"

# Keep the screen awake for the whole run, otherwise the emulator dozes and
# uiautomator dumps the launcher instead of the app.
wake_screen

if (( DO_CLEANUP )); then
    stage_cleanup
    print_summary || exit 1
    exit 0
fi

if (( DO_SEED )); then
    stage_install

    step "Resetting the app data so exactly one alarm is seeded"
    # The app data can only be touched while the user is unlocked.
    if ! user_is_unlocked; then
        info "the device is locked - unlocking it before seeding"
        unlock_device || die "could not unlock the device"
    fi
    ensure_app_installed || die "could not install $PKG"
    if adb_cmd shell pm clear "$PKG" >/dev/null 2>&1; then
        info "app data cleared"
    else
        warn "could not clear the app data (the device may be locked)"
    fi
    # pm clear revokes the runtime permissions; re-grant the notification
    # permission so the app does not show a permission dialog on launch.
    adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

    seed_alarm

    step "Verifying that the app registered the alarm with AlarmManager"
    sleep 2
    stage_before

    # The screen lock is installed LAST, on purpose: the alarm has to be seeded
    # while the device is unlocked (the schedule lives in credential-encrypted
    # storage), and only then do we lock the device so that the reboot lands in
    # the direct-boot state.
    stage_lock

    step "Locking the device before the reboot"
    if lock_device; then
        pass "device_locked_before_reboot" "State: $(user_state)"
    else
        # Not fatal: on the emulator the PIN takes effect asynchronously and the
        # reboot may still land in the locked state, which is what the
        # user_locked_after_reboot assertion checks.
        warn "the device still reports $(user_state) - the reboot may still land locked"
    fi
fi

if (( DO_REBOOT )); then
    stage_reboot
    stage_preunlock_checks
fi

if (( DO_WAIT )); then
    stage_wait_for_alarm
fi

if (( DO_UNLOCK )); then
    stage_unlock
fi

print_summary || exit 1
