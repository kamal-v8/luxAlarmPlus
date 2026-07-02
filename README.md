# Lux Alarm

**Lux Alarm** is a light-sensitive alarm clock designed to ensure you get out of bed. The alarm remains active until it detects a specific level of ambient light in your room.

## How It Works

To disable the alarm, you must increase the room's brightness, either by opening your blinds or turning on a light. The app utilizes your phone's built-in **ambient light sensor** to measure the brightness level, preventing you from simply hitting "snooze" while remaining in the dark.

## Key Features

* **Light-Based Deactivation:** The alarm only stops once a pre-defined light threshold is met.
* **Adjustable Sensitivity:** Customize the required brightness level to account for different environments or weather conditions.
* **Lux Hold Timer:** Require the light to stay above the threshold for 5-120 seconds before the alarm can be dismissed.
* **Lock Screen Pinning:** Optional lock-task mode to prevent bypassing the alarm on the lock screen.
* **Modern Interface:** A clean, minimal UI built using **Material Design 3**.

## Installation

### Obtainium (Recommended)

The easiest way to install and keep Lux Alarm automatically updated.

[<img src="https://obtainium.imranr.dev/badge_obtainium.png"
    alt="Get it on Obtainium"
    height="80">](https://obtainium.imranr.dev/add.html?url=https://github.com/kamal-v8/LuxAlarm)

Or add manually in Obtainium:
1. Open Obtainium → **Add App**
2. Enter the app source URL: `https://github.com/kamal-v8/LuxAlarm`
3. Obtainium will automatically detect releases and offer updates
4. Tap **Add** and the app will be tracked for updates

### GitHub Releases

Download the latest APK from the [Releases](../../releases) page.

## Building from Source

```bash
# Clone the repository
git clone https://github.com/kamal-v8/LuxAlarm.git
cd LuxAlarm

# Build debug APK
./gradlew assembleDebug

# Build release APK (unsigned)
./gradlew assembleRelease
```

## Signing Release Builds

To sign your release builds:

1. Generate a keystore:
```bash
keytool -genkey -v -keystore luxalarm.keystore -alias luxalarm -keyalg RSA -keysize 2048 -validity 10000
```

2. Add signing configuration to `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("-keystore path-")
            storePassword = "-password-"
            keyAlias = "-alias-"
            keyPassword = "-password-"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

**Never commit your keystore or passwords to version control.**

## Releasing

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Commit and push to GitHub
3. Create and push a tag:
```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
git push origin vX.Y.Z
```
4. GitHub Actions will automatically build and attach the APK to a new release
5. Optionally edit the release notes on GitHub

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
