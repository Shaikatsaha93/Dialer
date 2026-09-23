# Dialer

A native Android SIP/VoIP softphone for call-center agents, built with Kotlin, Jetpack Compose and the [Linphone SDK](https://www.linphone.org/).

It registers to a SIP server (tested with **iTelSwitchPlus 8.0.0**), places and receives calls, runs 3-way conference calls on the device, and shows the account's prepaid balance on the home screen.

> Developed by Shaikat.

## Features

- **SIP calling:** register over UDP, TCP or TLS, make and receive calls, DTMF keypad, mute, speaker/earpiece, hold.
- **3-way conference:** add a second party during a call. The call is mixed on the phone by Linphone's local conference. You can remove participants or leave and rejoin the conference.
- **Account balance:** reads the prepaid balance that iTelSwitchPlus sends on every registration and shows it on the dialer status card. It refreshes about once a minute and after every call, and turns red below 5.
- **Multiple SIP accounts** stored locally with Room. One account is active at a time.
- **Call history:** incoming, outgoing and missed calls, with a filter.
- **Device contacts:** search contacts and dial or add them to a call.
- **Android Telecom integration** through a self-managed `ConnectionService`, a foreground service and an ongoing-call notification with Hang up, Mute and Speaker actions.
- **Background operation:** wake lock and Wi-Fi lock during calls, lock-screen display, optional auto-answer.
- **Firebase Cloud Messaging:** receives push messages. A push with `type=call` (or a `caller_uri` field) shows the incoming-call screen.
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
├── service/        Foreground call service, FCM messaging service
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
- **Registration fails.** The Settings screen shows the SIP error code: 401/407 for wrong credentials, 403 for a forbidden or blocked account, 404 for an unknown user, 408 for an unreachable server.
