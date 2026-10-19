# luxAlarm+ direct-boot test harness

`tools/boot-alarm-test.sh` automates the end-to-end check for the direct-boot
feature:

> After a reboot in which the device is **never unlocked**, luxAlarm+ must
> already have its alarm registered with `AlarmManager`, so the alarm still
> rings at the right time.

The alarm schedule itself lives in a Room database in **credential-encrypted
(CE)** storage, which is only readable after the user entered their
credential. The feature adds a mirror of the schedule in **device-protected
(DE)** storage plus a `directBootAware` `BootReceiver` that re-registers the
alarm with `AlarmManager.setAlarmClock()` while the device is still locked.

To exercise that path the emulator **needs a screen lock** – without one the
CE storage is always available, the user is never `RUNNING_LOCKED` and the
direct-boot branch is never taken. The harness therefore installs a PIN
(`1234` by default) before the reboot.

---

## Usage

```bash
# full flow: seed -> capture "before" -> reboot -> pre-unlock assertions -> unlock
tools/boot-alarm-test.sh

# run it in stages
tools/boot-alarm-test.sh --seed      # seed one alarm and capture the "before" state
tools/boot-alarm-test.sh --reboot    # reboot and run the pre-unlock assertions
tools/boot-alarm-test.sh --wait      # wait for the alarm to fire, the device stays locked
tools/boot-alarm-test.sh --unlock    # unlock and run the post-unlock assertions
tools/boot-alarm-test.sh --cleanup   # remove the screen lock and uninstall the app

tools/boot-alarm-test.sh --help
```

Options:

| option | meaning |
| ------ | ------- |
| `--seed` | only seed an alarm and capture the "before reboot" state |
| `--reboot` | only reboot and run the pre-unlock assertions |
| `--wait` | only wait for the alarm to fire (the device stays locked the whole time) |
| `--unlock` | only unlock and run the post-unlock assertions |
| `--cleanup` | remove the screen lock (`locksettings clear`) and uninstall the app |
| `--all` | same as the default (full flow) |
| `--serial <id>` | target a specific device (also `ANDROID_SERIAL`) |
| `--pin <pin>` | screen lock PIN (default `1234`) |
| `--log-dir <path>` | where to write the log files |

Environment variables: `LUXALARM_PIN`, `LUXALARM_APK`, `LUXALARM_LOG_DIR`,
`LUXALARM_SEED_MINUTES`, `LUXALARM_SEED_MODE`, `LUXALARM_WAIT_FOR_ALARM`,
`ANDROID_SERIAL`, `ADB`.

The script exits with `0` when every assertion passed, `1` when an assertion
failed and `2` on a fatal error (no device, no APK, seeding failed, ...).

### What it does, step by step

1. `adb wait-for-device` – fails loudly if no emulator is attached.
2. Installs `app/build/outputs/apk/debug/app-debug.apk`
   (if the APK is missing it tells you to run `./gradlew :app:assembleDebug`
   and exits).
3. Makes sure a screen lock is set (`locksettings set-pin 1234`; an already
   existing PIN is accepted and verified).
4. Seeds **exactly one** alarm a few minutes in the future (see below).
5. Captures the "before" state: `dumpsys alarm` is saved to
   `dumpsys-alarm-before.txt` and the pending alarm of the app is reported.
6. Reboots the device **without unlocking it** (`svc power reboot`, falling
   back to `adb reboot`) and waits for the boot to complete.
7. Asserts that the user is still locked: `dumpsys user` has to show
   `State: RUNNING_LOCKED`.
8. Asserts that the app ran pre-unlock: the logcat is searched for the
   boot/reschedule lines of the app (`BootReceiver`, `AlarmRepository`,
   `DeviceProtectedStore`).
9. **Key assertion**: `dumpsys alarm` has to show a pending alarm for
   `com.dsalmun.luxalarmplus` *while the device is still locked*, including
   its trigger time.
10. Unlocks the device (PIN bouncer -> keypad, fallback `input text`,
    fallback `locksettings clear --old`) and asserts that the user state
    becomes `RUNNING_UNLOCKED`.
11. Prints a PASS/FAIL summary and where the log files are.

### Assertions

| name | meaning |
| ---- | ------- |
| `device_online` | the emulator is attached and booted |
| `apk_installed` | the debug APK was installed |
| `screen_lock_set` | a PIN is set on the device |
| `alarm_registered_before` | the seeded alarm is pending in `AlarmManager` |
| `user_locked_after_reboot` | `dumpsys user` shows `RUNNING_LOCKED` |
| `app_ran_pre_unlock` | the app logged its boot/reschedule work |
| `boot_receiver_ran` | the `BootReceiver` handled the boot broadcast |
| `alarm_registered_while_locked` | **the alarm is pending in `AlarmManager` while the user is still locked** |
| `user_unlocked` | the device could be unlocked |
| `alarm_registered_after_unlock` | the alarm is still pending after the unlock |

`alarm_registered_while_locked` is the assertion that fails before the
direct-boot feature lands – that is the point of the harness.

A green run looks like this:

```
PASS user_locked_after_reboot         State: RUNNING_LOCKED
PASS app_ran_pre_unlock               4 matching log line(s)
PASS boot_receiver_ran                the BootReceiver handled the boot broadcast
PASS alarm_registered_while_locked    pending alarm present for com.dsalmun.luxalarmplus
PASS user_unlocked                    State: RUNNING_UNLOCKED
```

with this in the logcat (`logcat-post-reboot.txt`):

```
I BootReceiver: Boot detected (android.intent.action.LOCKED_BOOT_COMPLETED):
    rescheduling from device-protected snapshot (locked)
I AlarmRepository: loadActiveAlarms: [AlarmItem(id=1, hour=22, minute=35, ...)]
    from the device-protected snapshot
I AlarmRepository: rescheduleAfterBoot: no missed alarms, scheduling next
```

---

## How the alarm is seeded

The app has **no debug/test intent hook** – `MainActivity` only understands
`ACTION_MAIN`, there is no extra that seeds an alarm. Two routes are
implemented, selectable with `LUXALARM_SEED_MODE`:

### `instrumentation` (default)

The `SeedAlarmTest` instrumentation test of the app writes exactly one alarm
into the Room database and calls `scheduleNextAlarm()`, which registers it
with `AlarmManager` and refreshes the device-protected mirror:

```bash
adb shell am instrument -w -r \
    -e minutesAhead 10 \
    -e class com.dsalmun.luxalarmplus.SeedAlarmTest \
    com.dsalmun.luxalarmplus.test/androidx.test.runner.AndroidJUnitRunner
```

The test prints `SEED ...` lines; the harness reads the trigger time from
them. The androidTest APK is installed automatically (it is built with
`./gradlew :app:assembleDebugAndroidTest` if it is missing).

### `ui`

`LUXALARM_SEED_MODE=ui` drives the app UI with `uiautomator` instead – no
test APK needed:

```bash
adb shell am start -W -n com.dsalmun.luxalarmplus/.MainActivity
adb shell uiautomator dump /data/local/tmp/luxalarm-ui.xml
adb pull /data/local/tmp/luxalarm-ui.xml ui-dump.xml
adb shell input tap <x> <y>        # "Add Alarm" FAB -> hour -> minute -> "Set"
```

The Material3 time picker exposes the hours `0..23` as
`content-desc="N hours"` and the minutes in 5-minute steps as
`content-desc="N minutes"`; the header repeats the current value as a
`TextView`, so the dial entries are picked with the additional class filter
`android.view.View`. The target time is `now + 10 minutes` rounded up to the
next 5-minute step (the granularity the dial offers).

The harness always works with the **device** clock and time zone
(`adb shell date`), never with the host clock.

---

## Notes and gotchas

* `adb root` does not work on this emulator image (`google_apis_playstore`,
  production build). Everything is done with plain `adb shell` as the `shell`
  user. `locksettings set-pin` / `locksettings verify --old` do work.
* `svc power reboot` fails on this image with *"Attempt to invoke interface
  method 'void android.os.IPowerManager.reboot(...)' on a null object
  reference"*. The harness detects that and falls back to `adb reboot`.
* Unlocking uses a chain of fallbacks, because the keyguard behaves
  differently depending on the state the emulator was left in:
  1. the numeric keypad of the PIN bouncer (`com.android.systemui:id/key0`
     … `key9`, `key_enter`),
  2. the text field of the bouncer (`input text` + `ENTER`, optionally the
     enter key of the soft keyboard),
  3. `locksettings clear --old <pin>` – this removes the credential, so the
     keyguard disappears. The harness re-installs the PIN right afterwards so
     that the next run starts from a locked device again.
* The emulator dozes while the screen is off, the keyguard takes over the
  screen and `uiautomator` then dumps the launcher instead of the app. The
  harness therefore sets `svc power stayon true` and wakes the screen before
  it touches the UI.
* Random first-run dialogs of the pre-installed Google apps (Play Store
  asking for the camera, Maps asking to sign in, ...) cover the app from time
  to time. The harness dismisses them (permission dialogs of the app itself
  are accepted, foreign ones are denied, "Skip"/"Not now"/"Cancel"/"OK" are
  tapped, otherwise it goes back).
* The harness **must not run while a Gradle build or `connectedAndroidTest`
  run touches the same emulator**: Gradle clears the app data
  (`pm clear`) before the tests and reinstalls the app, which cancels the
  pending alarm and invalidates the run. If the alarm suddenly disappears
  from `dumpsys alarm` look for `Reason=data_cleared` in the *Removal
  history* of the dump.
* The script re-executes itself from a private copy in `$TMPDIR` before it
  does anything else: bash reads the script file while it runs, so editing
  `tools/boot-alarm-test.sh` during a run would otherwise abort the run with
  a syntax error in the middle of the flow.
* `/sdcard` is not readable while the device is locked, so the UI dumps go
  to `/data/local/tmp`.
* `dumpsys alarm` prints the package of a pending alarm at the end of the
  `Alarm{...}` line, e.g.
  `RTC_WAKEUP #13: Alarm{1fe1013 type 0 origWhen 1792428600000 ... com.dsalmun.luxalarmplus}`.
  The harness only looks at the *"N pending alarms"* block, not at the
  *"Removal history"* (which only lists alarms that were cancelled).

## Log files

Everything is written to `tools/logs/<timestamp>/` (override with
`--log-dir` / `LUXALARM_LOG_DIR`):

| file | content |
| ---- | ------- |
| `run.log` | the full console output of the run |
| `install.log` | output of `adb install` |
| `seed-instrumentation.log` | output of the seeding instrumentation |
| `dumpsys-alarm-before.txt` | `dumpsys alarm` before the reboot |
| `dumpsys-alarm-locked.txt` | `dumpsys alarm` while the device is locked |
| `dumpsys-alarm-unlocked.txt` | `dumpsys alarm` after the unlock |
| `dumpsys-alarm-*.txt-excerpt.txt` | the relevant excerpt of the dumps |
| `logcat-post-reboot.txt` | the full logcat after the reboot |
| `logcat-tags-post-reboot.txt` | the logcat of the app tags only |
| `logcat-post-unlock.txt` | the logcat after the unlock |
| `ui-*.xml` | the `uiautomator` dumps of the seeding steps |

## Requirements

* `bash` 4+, `adb` in `~/Android/Sdk/platform-tools` (added to `PATH` by the
  script).
* A running emulator with a user that can be locked (a PIN is enough).
* The debug APK of the app; the androidTest APK for the default seeding mode.

---

# Icon layer verification

The launcher shows two renderings of the same icon side by side: the full-colour
artwork (`mipmap-*/ic_launcher_foreground.webp`) and the themed/monochrome
mask (`drawable/ic_launcher_monochrome.xml`), which Android 13+ tints with the
user's Material You colour. They have to read as the same icon, so there is
tooling to prove it.

## `tools/compare-icon-layers.py`

Renders both layers into the same 108x108 grid and compares their silhouettes:

* the ring's inner/outer edge radius as a function of the angle around the
  clock centre
* the sun's bounding box and the height of its flat bottom edge
* the rays (the ink outside the ring band) and their endpoints
* the tick marks inside the face

It prints a per-angle table, the worst deviation of every feature, a verdict
against a tolerance (1.0 viewport unit for the ring, 1.5 for the sun and the
rays) and writes a side-by-side PNG plus a 4x zoom of the sun/ring junction to
`/tmp/opencode/`. Exit code 0 means the layers match.

```bash
tools/compare-icon-layers.py              # report + verdict
tools/compare-icon-layers.py --tolerance 2.0
```

## `tools/verify-icon-on-device.sh`

Builds the debug APK, installs it, screenshots the launcher and puts the
themed icon (cropped from the screenshot) next to the full-colour one:

```bash
tools/verify-icon-on-device.sh
```

It never reboots the device and never touches the screen lock, so it can run
side by side with the boot harness.
