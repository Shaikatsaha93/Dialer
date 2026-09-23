# Dialer

A native Android SIP/VoIP softphone for call-center agents, built with Kotlin, Jetpack Compose and the [Linphone SDK](https://www.linphone.org/).

It registers to a SIP server (tested with **iTelSwitchPlus 8.0.0**), places and receives calls, runs 3-way conference calls on the device, and shows the account's prepaid balance on the home screen.

> Developed by Shaikat.

## Features

- **SIP calling:** register over UDP, TCP or TLS, make and receive calls, DTMF keypad, mute, speaker/earpiece, hold.
- **3-way conference:** add a second party during a call. The call is mixed on the phone by Linphone's local conference. You can remove participants or leave and rejoin the conference.
- **Account balance:** reads the prepaid balance that iTelSwitchPlus sends on every registration and shows it on the dialer status card. It refreshes about once a minute and after every call, and turns red below 5.
- **Multiple SIP accounts** stored locally with Room. One account is active at a time.
- **Call history:** incoming, outgoing and missed calls, with a filter. Each call is saved once, with the right direction.
- **Device contacts:** search contacts and dial or add them to a call.
- **Android Telecom integration** through a self-managed `ConnectionService`, a foreground service and an ongoing-call notification with Hang up, Mute and Speaker actions.
- **Rings in the background:** a standby service keeps the SIP account registered with the screen off, in Doze and in battery saver, on Wi-Fi or mobile data. It starts again by itself after a reboot or an app update. Incoming calls open a full-screen call screen over the lock screen. See [Receiving calls in the background](#receiving-calls-in-the-background).
- **Firebase Cloud Messaging:** a call push wakes the app and re-registers the SIP account so the real call can ring. Only real FCM tokens are shown. See [Push notifications (FCM)](#push-notifications-fcm).
- **Small APK:** the release build is about 31 MB (it was 150 MB). See [APK size](#apk-size).
- **Other:** optional auto-answer, wake lock and Wi-Fi lock during calls.
- **Glass design:** frosted-glass panels on a soft gradient backdrop, a floating tab bar, and Apple iOS system colors. Works in light and dark mode.
- **Settings:** echo cancellation, adaptive rate control, mic gain, STUN, IPv6, keep-alive, theme (System, Light or Dark).
- **Diagnostics:** in-app SIP log viewer. SDK logs are also mirrored to logcat under the `LinphoneSdk` tag.

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| SIP / media | Linphone SDK for Android 5.2 (`org.linphone:linphone-sdk-android:5.2.+`) |
| Storage | Room |
| Push | Firebase Cloud Messaging |
| Build | Android Gradle Plugin 9.1, Gradle 9.3.1 |
| Android | minSdk 24, targetSdk / compileSdk 36 |

## Project structure

```
app/src/main/java/com/example/
├── sip/            SipManager: Linphone core, registration, calls, conference, balance
├── telecom/        ConnectionService and PhoneAccount registration
├── service/        Standby/call foreground service, FCM service, boot receiver,
│                   battery and full-screen permission checks
├── data/
│   ├── model/      CallState, SipAccount, AccountBalance, AppSettings, …
│   ├── local/      Room database and DAOs
│   └── repository/ Accounts, call log, contacts, settings, FCM token
└── ui/
    ├── screens/    Dialer, Active call, Contacts, History, Account settings
    ├── components/ Keypad, cards, settings sections
    ├── theme/      Colors, typography, glass style (Glass.kt)
    └── viewmodel/  SoftphoneViewModel
```

Almost all call logic is in [`SipManager.kt`](app/src/main/java/com/example/sip/SipManager.kt).

## Design

The UI uses a frosted-glass style similar to iOS "Liquid Glass", with Apple's iOS system colors.

- **Backdrop:** a soft gradient with faint system-blue glows, drawn behind every screen by `GlassBackground`.
- **Glass panels:** the `Modifier.glass(shape, colors)` extension draws a translucent fill, a light sheen at the top and a bright hairline edge. Keypad keys, the account status card, the number field, call controls and list cards use it. Active buttons, such as Mute when it is on, are filled with their own color.
- **Floating tab bar:** the bottom navigation is a rounded glass bar that floats above the screen edge.
- **System bars:** status and navigation bar icons follow the app theme, so they stay readable in dark mode.

| | Light | Dark |
|---|---|---|
| Accent (buttons, selected tab, links) | `#007AFF` | `#0A84FF` |
| Background | `#F2F2F7` | `#000000` |
| Call button | `#34C759` | `#34C759` |
| Hang up button | `#FF3B30` | `#FF3B30` |
| Primary text | `#1C1C1E` | `#F2F2F7` |
| Secondary text | `#6C6C70` | `#98989F` |

To change the look, edit two files:

- [`ui/theme/Color.kt`](app/src/main/java/com/example/ui/theme/Color.kt): accent, text and call-button colors.
- [`ui/theme/Glass.kt`](app/src/main/java/com/example/ui/theme/Glass.kt): glass transparency, edge brightness, and the backdrop gradient and glow colors (`LightGlassColors`, `DarkGlassColors`).

The glass panels are translucent but do not blur the content behind them. The backdrop is already soft, so they read as frosted glass and run smoothly on Android 7 and newer. Real backdrop blur would need a library such as Haze and works only on Android 12 and newer.

## Build and run

### Requirements

- Android Studio with Android SDK 36.
- JDK 17 or newer.
- `app/google-services.json` from your Firebase project. It is not committed. Without it the build still runs but prints a warning, and push messaging will not work.

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/Shaikatsaha93/Dialer.git
   ```
2. Open the folder in Android Studio and let Gradle sync.
3. Put your `google-services.json` in `app/`.
4. Run the `app` configuration on a device or emulator.

The Gradle wrapper scripts (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) are not in the repository yet. Android Studio creates them, or you can run `gradle wrapper` once. After that you can build from the command line:

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on a connected device
```

### Signing

- **Debug** builds use `debug.keystore` in the project root. It is not committed; add your own.
- **Release** builds read the keystore from the `KEYSTORE_PATH` environment variable (default `my-upload-key.jks` in the project root), with `STORE_PASSWORD` and `KEY_PASSWORD` for the passwords.

If a device already has the app signed with a different key, Android refuses the update. Uninstall the old app first; this deletes its saved accounts and history.

## Configure a SIP account

Open **Settings** and add an account:

| Field | Example |
|---|---|
| Username | your SIP number, e.g. `0967XXXXXXX` |
| Password | your SIP password |
| Domain / server | your SIP server IP or hostname |
| Port | `5060` |
| Transport | UDP |

When registration succeeds, the dialer shows **SIP Online • Ready to Call** and the balance.

## Making a 3-way call

1. Call the first party (A).
2. During the call, tap **Conference**, enter the second number (B) and tap **Call & Add**.
3. A stays live in the conference while B rings. B joins automatically when they answer.
4. Tap the end-call button to hang up everyone.

A is not put on SIP hold while B rings; see the server notes below for why.

## Receiving calls in the background

The app rings without being open: with the screen off, in Doze, in battery saver, and on Wi-Fi or mobile data.

**How it works**

- **Standby service.** While **Keep App Running in Background** is on (the default), a foreground service shows the *SIP Softphone Standby* notification and keeps the account registered. It keeps running after a call ends.
- **Service type.** When idle, the service runs as `specialUse`. While a call rings or is active, it switches to `phoneCall`. Android 15 and newer do not allow a `phoneCall` service to start at boot.
- **Reboot and update.** `BootReceiver` starts the standby service after `BOOT_COMPLETED` and after an app update, so the user does not have to open the app.
- **Keepalive.** A small UDP keepalive is sent every 20 seconds. Mobile-carrier NATs often drop an idle UDP mapping after about 30 seconds, and the switch's INVITE would then never reach the phone. The switch also asks for a re-REGISTER about every minute.
- **Lock screen.** An incoming call uses a full-screen notification, so the call screen opens over the lock screen like WhatsApp instead of showing only a banner.

On a Pixel 7 Pro (Android 17), forced into deep Doze with battery saver on, the registration was still refreshed with `200 OK` every 54 seconds.

**What the phone must allow**

**Settings → Background & Lock Screen** shows a status card with a green tick or a red warning and an **Allow** button for each item:

| Permission | Why |
|---|---|
| Battery optimization: *Unrestricted* | Otherwise Doze can cut the app's network and the phone may stop the app. The app asks once on first start. |
| Full-screen incoming call | Otherwise a locked phone shows only a small banner. |

On Xiaomi, Oppo, Vivo, Realme and Samsung phones, also turn on **Autostart** or **Allow background activity** in the app's App info. These phones stop background apps more aggressively than stock Android.

**Limitation.** If the user taps **Force stop** in App info, Android does not let the app start again until it is opened by hand. To ring even in that state, the SIP server must send an FCM push (next section).

## Push notifications (FCM)

The app is ready to be woken by a push. The push itself has to come from the server side, because only the switch knows when a call arrives.

**What the app does with a push**

A data message is treated as a call push if it has `type=call` (or `incoming_call`), `action=call`, `caller_uri`, `caller` or `pn_sip_call_id`. On a call push the app:

1. starts the standby service (a high-priority FCM message allows this from the background);
2. re-registers the SIP account if it is not registered;
3. waits for the switch's real INVITE, which rings through the normal incoming-call path.

The push does not create a call by itself.

Any other push is shown as a normal notification. Both switches are in **Settings → Push Notifications (FCM)**.

**Example push** (FCM HTTP v1, must be a *data* message with high priority):

```json
{
  "message": {
    "token": "<device FCM token>",
    "android": { "priority": "high" },
    "data": { "type": "call", "caller_uri": "sip:01XXXXXXXXX@203.76.101.50", "caller_name": "Customer" }
  }
}
```

**What the server side needs**

- The device's FCM token. The token is shown in the app. It is not yet sent to any server.
- Something that sends the push when a call comes in for this account. This can be the switch itself (RFC 8599 push parameters in the REGISTER `Contact`), a push proxy such as Flexisip in front of it, or a webhook.
- The switch must hold or retry the INVITE for a few seconds until the phone has registered again.

Ask your provider whether iTelSwitchPlus supports FCM or RFC 8599 push. Until it does, the standby service above is what keeps incoming calls working.

## APK size

The release APK is about **31 MB** (it was about 150 MB):

- **R8** minifies and shrinks code and resources. `proguard-rules.pro` keeps `org.linphone.**`, because the native library calls those classes through JNI.
- **ABIs:** release builds include only `arm64-v8a` and `armeabi-v7a` (real phones). Debug builds include `arm64-v8a` and `x86_64` for the emulator. Linphone's native code is about 30 MB per ABI.
- **Compressed native libraries** (`jniLibs.useLegacyPackaging = true`). Android extracts them once at install time.
- **Unused dependencies removed:** Retrofit, Moshi, OkHttp, Firebase AI and App Check. They are commented out in `app/build.gradle.kts` so they are easy to add back.

## Notes for iTelSwitchPlus servers

These behaviours were found in SIP traces and shaped the implementation:

- **Codec echo on re-INVITE.** The switch answers a re-INVITE by echoing the whole SDP offer instead of choosing one codec. If the offer lists opus first, Linphone switches that leg to opus while the carrier keeps sending PCMU. One side then hears noise and the other hears nothing. The app therefore offers only **PCMU and PCMA** (DTMF is sent as RFC 2833).
- **Held calls are dropped after about 30 seconds**, even with hold music playing. That is why a 3-way call puts the first party straight into the conference instead of holding it.
- **Balance header.** Every `200 OK` to `REGISTER` carries:
  ```
  iTelSwitchPlus: Balance=18.23 Credit-Limit=0.0 Total-Limit=18.23 Currency=BDT status=1
  ```
  Call answers carry `X-iTelSwitchPlus: Rate=… TalkTime=…`.
- **No balance:** calls fail with `403 Forbidden` and `Reason: … "Client Does not have sufficient balance to make call"`.

## Troubleshooting

- **Conference audio is choppy or silent on the emulator.** The emulator's audio driver drops frames and its CPU is too slow for the conference mixer. Test conference calls on a real phone.
- **Collect logs** from a connected device:
  ```bash
  adb logcat -s LinphoneSipManager LinphoneSdk
  ```
  During a conference, `[Conf Stats]` lines appear every 2 seconds with each leg's codec, direction and bandwidth. `in=0.0kbps` on a leg means that party's audio is not reaching the phone.
- **Calls are missed when the phone is locked.** Open **Settings → Background & Lock Screen** and make sure both items show a green tick. Check that the *SIP Softphone Standby* notification is present. On Xiaomi, Oppo, Vivo, Realme and Samsung phones also allow Autostart.
- **Registration fails.** The Settings screen shows the SIP error code: 401/407 for wrong credentials, 403 for a forbidden or blocked account, 404 for an unknown user, 408 for an unreachable server.
