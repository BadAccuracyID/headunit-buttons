# Headunit Buttons

Capture and remap car buttons on a rooted GT6 Android headunit using an Xposed module.
Version 0.7 has been tested with the `com.szchoiceway.eventcenter` firmware on GT6-CAR.

## Verified behavior

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
- Reverse-camera and powered-off states keep stock handling. The Voice-on adapter also checks the firmware's MIPI reverse modes.
- An app launch consumes its original button action only after Android accepts the launch request.
- Voice on consumes the rest of an accepted press, including hold and release, to prevent a second stock action.
- Disabling mappings prevents new actions while finishing that already consumed gesture. A missing release expires after ten seconds.

The module does not read the serial device separately, send MCU configuration commands, or change WiFi/hotspot settings.
It observes bytes that EventCenter has already read.
The exported configuration provider accepts this app and EventCenter's UID; other packages sharing that UID share the access boundary.

The camera/Home and Navi/ZLink behaviors passed physical tests on the GT6-CAR.
Activation across another boot, separate long/double-press actions, and Siri-specific commands remain unverified or unimplemented.

## Diagnostics and tests

`./test.sh` runs 64 host checks for mapping rules, configuration expiry, packet decoding, voice gesture handling, and camera/Home selection.
These checks do not replace physical button tests.

The app retains up to 100 recent events in memory and displays the newest 20.
Mappings persist across app restarts.
**Record all MCU packets for 10 minutes** enables additional serial and packet logging:

```sh
adb -s YOUR_DEVICE logcat -s HeadunitButtons HeadunitSerial
```

The ADB launch option `--ez capture_raw true` also starts that diagnostic window.
Diagnostic recording itself does not consume input. Only explicitly enabled remapping changes button handling.

## References

- [Xposed Framework API](https://api.xposed.info/reference/packages.html)
- [Vector](https://github.com/JingMatrix/Vector)

This repository contains the app and its tests. It does not contain headunit firmware or KSW Toolkit code.
