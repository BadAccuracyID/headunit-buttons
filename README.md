# GT6 Utility (Headunit Buttons)

Capture and remap car buttons, and schedule screen brightness, on a rooted GT6 Android headunit using an Xposed module.
Candidate version 0.12 fixes Voice gesture handling across camera transitions and missing releases. It has not been installed or physically tested.
Version 0.11 fixes diagnostic recording restarting after boot. Recording now lasts at most 60 seconds, can be stopped, and is cleared when the app process restarts.
Version 0.9 adds the brightness schedule and renames the app to GT6 Utility; the package stays `com.efran.buttons`.
Version 0.8 adds selection-aware CarPlay opening. Version 0.7 has been tested with the `com.szchoiceway.eventcenter` firmware on GT6-CAR.

## Previously tested behavior

| Button | Available behavior tested on the headunit |
| --- | --- |
| Steering-wheel Voice on | Open the 360 camera; press again while it is focused to send Home |
| Navi | Open ZLink for CarPlay |
| Star / Home | Keep the original Home action |

The camera toggle targets `com.ivicar.avm`. The ZLink mapping targets `com.zjinnova.zlink`.
Fresh installs start with remapping disabled and no mappings.

The app captures semantic button actions and MCU button packets. Semantic actions can open an app, send a supported firmware action, or do nothing.
Voice on has a separate adapter because this firmware routes it around the semantic handler.

Voice off has no identified input on the tested headunit. Tel reports a switch to the stock display, without a distinct button event.
Neither has a remapping adapter. Compatibility with other firmware is unverified.

## Requirements

- A rooted headunit with the supported EventCenter firmware.
- An Xposed-compatible framework with legacy module support. The tested setup uses Vector 2.2 build 3080 and Magisk 30.7 with Zygisk enabled.
- Android API 28 or later. The tested headunit reports API 33.

The adapter checks expected firmware methods and fields before installing its hooks.
It is scoped only to `com.szchoiceway.eventcenter`.

## Build

The build uses Bash, JDK 17 or newer, Android SDK platform 35, build-tools 35.0.0, `curl`, and `zip`.
It uses the command-line SDK tools directly, without Gradle.

Set your SDK and JDK locations:

```sh
export ANDROID_SDK=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'

./fetch-deps.sh
./test.sh
./build.sh
```

`fetch-deps.sh` downloads Xposed API 82 from its official repository and checks its SHA-256 hash.
The API is a compile-only dependency. It is not bundled into the APK.
You can set `XPOSED_API_JAR` to use an existing copy instead.

The build also accepts `ANDROID_HOME` or `ANDROID_SDK_ROOT`, and `BUTTONS_JDK` overrides `JAVA_HOME`.
On macOS, the default paths use Homebrew's Android command-line tools and Android Studio's bundled JDK.

Output: `headunit-buttons.apk`, package `com.efran.buttons`.
The build generates a local development signing key on first use and preserves it for subsequent updates.
Build outputs, downloaded dependencies, and signing keys are excluded from Git.

## Install and configure

```sh
adb -s YOUR_DEVICE install -r headunit-buttons.apk
```

1. Enable **Headunit Buttons** in the framework manager, scoped to `com.szchoiceway.eventcenter`.
2. Restart EventCenter or reboot the headunit so the framework loads the module.
3. Open **Headunit Buttons** and check for **MCU capture and button handler connected**.
4. Choose **Set Voice on action → 360 camera / Home**, and **Set Navi action → Open an app → ZLink (CarPlay)**.
5. Turn on **Enable remapping** and test the physical buttons while parked.

The camera toggle checks the focused task across displays. This matters because the tested camera app runs on display 2.
When the camera is focused, the adapter sends the same Home key used by the firmware's Home action.
Otherwise, it opens the camera app.

To change another captured semantic button, tap its event and choose an action.
To restore stock behavior, turn off **Enable remapping** or choose **Keep original action** for that button.
To remove the hooks, disable the module in the framework manager before EventCenter next starts.

## Behavior and limits

- Configuration refreshes every 500 milliseconds. Failed refreshes disable new overrides; configurations expire after 2.5 seconds.
- Actual reverse and powered-off states keep stock handling. Voice checks reverse flags, gear, the camera helper, and MIPI mode.
- The firmware uses `MODE_REVERSE_360` for ordinary camera views too. That mode alone no longer blocks the camera/Home action.
- An app launch consumes its original button action only after Android accepts the launch request.
- Voice on consumes the rest of an accepted press, including hold and release, to prevent a second stock action.
- Disabling mappings prevents new actions while finishing an accepted gesture. Camera transitions also preserve ownership of its hold and release.
- A repeated down within 250 ms is consumed. A later down can start a new action if the preceding release was lost.
- This duplicate guard does not delay the first action or require holding the button.

The module does not read the serial device separately, send MCU configuration commands, or change WiFi/hotspot settings.
It observes bytes that EventCenter has already read.
The exported configuration provider accepts this app and EventCenter's UID; other packages sharing that UID share the access boundary.

Earlier builds passed short physical camera/Home and Navi/ZLink tests on the GT6-CAR.
On November 3, the user reported missed Voice presses and unintended Navi actions during longer use.
Candidate 0.12 addresses identified code paths. Its physical checks, boot checks, and long-hold behavior remain pending.
Separate long/double-press actions and Siri-specific commands remain unimplemented.

## Diagnostics and tests

`./test.sh` passes 91 policy checks and 28 brightness schedule checks.
The policy checks cover mapping, configuration expiry, packet decoding, Voice gestures, reverse protection, and camera/Home selection.
These checks do not replace physical button tests.

The app retains up to 100 recent events in memory and displays the newest 20.
Mappings persist across app restarts.
**Record all MCU packets for 60 seconds** enables additional serial and packet logging:

```sh
adb -s YOUR_DEVICE logcat -s HeadunitButtons HeadunitSerial
```

The ADB launch option `--ez capture_raw true` also starts that diagnostic window.
The app's stop button cancels recording early. The recording deadline is never saved across process restarts or reboots.
Diagnostic recording itself does not consume input. Only explicitly enabled remapping changes button handling.

## References

- [Xposed Framework API](https://api.xposed.info/reference/packages.html)
- [Vector](https://github.com/JingMatrix/Vector)

This repository contains the app and its tests. It does not contain headunit firmware or KSW Toolkit code.

## Selected CarPlay shortcut (0.8)

Choose **Set Navi action → Open selected CarPlay app** when CarPlay Switch is installed. Navi then opens the receiver saved by the switcher. The switcher preserves an active DiPlay session when reopening its screen. Use CarPlay Switch to change receivers. Missing switcher or a rejected activity launch leaves the stock Navi action available.

The updated hook requires EventCenter to restart or the headunit to reboot. Camera/power guards and the independent Voice-on camera/Home action remain active. The new selected-receiver route has host policy coverage; its physical and boot checks are pending.

## Screen brightness schedule (0.10)

The schedule sets the GT6 brightness level, the same 0-100 value as the GT6 Settings slider. Four periods each start at a configurable time and last until the next one; the last period wraps past midnight.

| Period | Starts | Level |
| --- | --- | --- |
| Morning | 05:30 | 100% |
| Noon | 11:00 | 100% |
| Afternoon | 15:00 | 100% |
| Evening | 18:00 | 50% |

The schedule is off on a fresh install. Turn on **Brightness schedule**, then tap a period to change its start time or level.

The adapter runs inside EventCenter and applies a level the way the GT6 Settings slider does. It writes EventCenter's brightness record and sends the level to the MCU backlight (`SendBLVal`). It then lets EventCenter set Android's brightness and the SystemUI slider (`sendBrightnessToSystemUI`).

- A level is applied when a period starts, when the schedule is edited, and when it is turned on. A level set by hand with a slider lasts until the next period.
- Reverse, camera, powered-off and backlight-off states keep stock handling. A pending change applies when they end.
- A failed configuration poll applies nothing. The minimum level is 5%.
- The app shows the last level the adapter applied and why.

Headlights: 0.9 had separate levels for headlights on and off. On the tested W205, the MCU never reports the light switch to EventCenter. A full capture of every MCU packet showed nothing while the switch was toggled, and `KSW_DATA_SMALL_LIGHT_ON` stays 0. The screen still dims when the headlights are on. That dimming happens below Android: it continued with EventCenter's night mode on and a night level of 100 sent to the MCU (`F2 00 6C 03 01 19 64`). So 0.10 schedules by time only. A saved 0.9 schedule falls back to the default.

The hook change needs EventCenter to restart, or the headunit to reboot. `./test.sh` covers the schedule logic.

## Candidate 0.12: Voice reliability

The earlier adapter could release an accepted Voice gesture to stock handling when the camera changed MIPI mode.
The firmware uses a shared saved button for hold/release handling. An old saved value can therefore trigger Navi from a Voice tail.
Candidate 0.12 consumes accepted tails before checking the current service, configuration, or mode.
It also replaces the ten-second missing-release lockout with the 250 ms duplicate-down window.

Passive November 3 observations did not capture a failed Voice press. The Navi explanation comes from the matching installed firmware and regression checks.
Normal camera views now permit Voice camera/Home actions. Actual reverse remains protected, including the Android 360 path that uses a separate reverse flag.

Both the GT6 Utility 0.12 and Media Remote 0.13 candidates need an EventCenter restart to load their updated hooks.
They remain uninstalled while the user is driving. Parked checks must cover quick presses, holds, camera transitions, both mute buttons, and reverse protection.
The 250 ms recovery rule still needs a physical long-hold check; host tests cannot establish the MCU's repeat behavior.
