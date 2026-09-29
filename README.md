# Dialer

A SIP/VoIP softphone for call-center agents that runs on **Android** and **Windows**, built with Kotlin, Compose Multiplatform and the [Linphone SDK](https://www.linphone.org/). Both apps share one code base for the screens, models, database and chat; iOS and web can be added on the same base later (see [Platforms](#platforms)).

It registers to a SIP server (tested with **iTelSwitchPlus 8.0.0**), places and receives calls, runs 3-way conference calls on the device, and shows the account's prepaid balance on the home screen. Each install must be approved by the admin before it can be used.

> Developed by Shaikat.

## Features

- **Admin approval and 30-day subscription:** a new install sends an access request and stays locked until the admin approves it. An approval lasts 30 days, then the app locks itself until renewed. The admin approves, renews and blocks devices from the **Admin** tab on their own phone and gets a notification for each new request. See [Access control (admin approval)](#access-control-admin-approval).
- **Windows app:** the same screens and features on a PC, with an installer (`Dialer-1.0.0.exe`). It keeps running in the system tray so calls still ring. See [Windows app](#windows-app).
- **Chat:** 1-to-1 and group text chat between SIP users over SIP MESSAGE (for example through Asterisk).
- **Call recording:** switch on in Settings to record every call to a WAV file on the device (Android: the app folder; Windows: Documents\Dialer\Recordings), with play, share/show and delete.
- **Edit SIP accounts:** change a saved account from Settings; it re-registers with the new details.
- **SIP calling:** register over UDP, TCP or TLS, make and receive calls, DTMF keypad, mute, speaker/earpiece, hold.
- **3-way conference:** add a second party during a call. The call is mixed on the phone by Linphone's local conference. You can remove participants or leave and rejoin the conference.
- **Account balance:** reads the prepaid balance that iTelSwitchPlus sends on every registration and shows it on the dialer status card, next to the SIP number. The currency (e.g. BDT) comes from the server; other SIP servers send no balance, so none is shown. It refreshes about once a minute and after every call, and turns red below 5.
- **Multiple SIP accounts** stored locally with Room. One account is active at a time.
- **Call history:** incoming, outgoing and missed calls, with a filter. Each call is saved once, with the right direction.
- **Device contacts:** search contacts and dial or add them to a call.
- **Android Telecom integration** through a self-managed `ConnectionService`, a foreground service and an ongoing-call notification with Hang up, Mute and Speaker actions.
- **Rings in the background:** a standby service keeps the SIP account registered with the screen off, in Doze and in battery saver, on Wi-Fi or mobile data. It starts again by itself after a reboot or an app update. Incoming calls open a full-screen call screen over the lock screen. See [Receiving calls in the background](#receiving-calls-in-the-background).
- **Firebase Cloud Messaging:** a call push wakes the app and re-registers the SIP account so the real call can ring. Only real FCM tokens are shown. See [Push notifications (FCM)](#push-notifications-fcm).
- **Small APK:** the release build is about 32 MB (it was 150 MB). See [APK size](#apk-size).
- **Other:** optional auto-answer, wake lock and Wi-Fi lock during calls.
- **Glass design:** frosted-glass panels on a soft gradient backdrop, a floating tab bar, and Apple iOS system colors. Works in light and dark mode.
- **Settings:** echo cancellation, adaptive rate control, mic gain, STUN, IPv6, keep-alive, theme (System, Light or Dark).
- **Diagnostics:** in-app SIP log viewer. SDK logs are also mirrored to logcat under the `LinphoneSdk` tag.

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.2, Kotlin Multiplatform |
| UI | Compose Multiplatform 1.9 (Jetpack Compose on Android), Material 3, Navigation Compose |
| SIP / media | Android: Linphone SDK 5.2 (`org.linphone:linphone-sdk-android:5.2.+`). Windows: liblinphone 5.5 (C API through JNA) |
| Storage | Room (KMP; bundled SQLite on Windows) |
| Access control | Firebase Authentication (anonymous) and Cloud Firestore; the Windows app uses their REST APIs |
| Push | Firebase Cloud Messaging |
| Build | Android Gradle Plugin 9.1, Gradle 9.3.1 |
| Android | minSdk 24, targetSdk / compileSdk 36 |

## Project structure

```
shared/            Code shared by all platforms (Kotlin Multiplatform)
  src/commonMain/kotlin/com/example/
  ├── AppGraph.kt       The services each app hands to the shared screens
  ├── platform/         Small helpers (time, dates, URL encoding) without JVM APIs
  ├── sip/              SipManager interface, CallEvent, chat headers
  ├── data/
  │   ├── model/        CallState, SipAccount, AccountBalance, AppSettings, Chat, …
  │   ├── local/        Room database and DAOs
  │   └── repository/   Accounts, call log, settings, chat, and the platform service
  │                     interfaces (LicenseService, AdminService, ContactsSource, …)
  └── ui/               Every screen, component, theme and SoftphoneViewModel
  src/androidMain/      Android-only UI: permissions, recordings card, battery card, FCM card
  src/desktopMain/      Windows-only UI: recordings card, toast
  src/jvmShared/        JVM helpers used by Android and Windows

app/               Android app
  src/main/java/com/example/
  ├── sip/              LinphoneSipManager (Linphone SDK for Android)
  ├── telecom/          ConnectionService and PhoneAccount registration
  ├── service/          Standby/call foreground service, FCM, boot receiver, notifications
  └── data/repository/  LicenseManager, AdminManager (Firebase SDK), contacts, FCM token

desktopApp/        Windows app
  src/main/kotlin/com/example/desktop/
  ├── Main.kt           Window, tray, approval gate
  ├── sip/              DesktopSipManager + LinphoneNative (liblinphone through JNA)
  └── firebase/         Firebase REST: approval (DesktopLicenseManager) and Admin tab
```

The Android call logic is in [`LinphoneSipManager.kt`](app/src/main/java/com/example/sip/LinphoneSipManager.kt), the Windows one in [`DesktopSipManager.kt`](desktopApp/src/main/kotlin/com/example/desktop/sip/DesktopSipManager.kt). The Firestore security rules are in [`firestore.rules`](firestore.rules).

## Platforms

| Platform | Status | SIP engine |
|---|---|---|
| Android | Done | Linphone SDK for Android |
| Windows | Done | liblinphone for Windows (JNA) |
| iOS | Not started | Would use Linphone for iOS, CallKit and PushKit. Needs a Mac and an Apple Developer account to build. iOS does not keep a SIP registration in the background, so the server must send VoIP pushes. |
| Web | Not started | Browsers cannot send UDP SIP, so it would use SIP.js over WebRTC. Needs SIP over WebSocket (WSS) on the server or a gateway such as Asterisk. |

Adding a platform means adding a target to `shared` and implementing the interfaces in `AppGraph` (SIP, approval, contacts, push) for it; the screens are reused as they are.

## Windows app

### Install

Run `Dialer-1.0.0.exe` (per-user install, no admin rights needed). It adds a Start menu entry and a desktop shortcut. The first start shows the same access-request screen as on Android; approve it from the Admin tab (phone or PC).

- Closing the window keeps Dialer in the **system tray** so the account stays registered and calls ring. Use **Quit** in the tray menu to exit.
- An incoming call brings the window to the front and shows a Windows notification.
- Audio uses the default Windows microphone and speakers/headset (change them in Windows sound settings).
- Data is stored in `%APPDATA%\Dialer` (database, Linphone state) and the Windows registry (settings, sign-in). Recordings go to `Documents\Dialer\Recordings`.
- No phone contacts on Windows; dial numbers directly.

### Build

```bash
./gradlew :desktopApp:run            # run from source
./gradlew :desktopApp:packageExe     # installer in desktopApp/build/compose/binaries/main/exe/
./gradlew :desktopApp:packageMsi     # MSI instead
```

The first build runs `fetchLinphoneWindows`, which downloads the official Linphone SDK for Windows (about 300 MB, once) and copies the needed DLLs, audio plugins, SIP grammars and sounds (about 57 MB) into `desktopApp/linphone/` (not committed). Building the installer needs Windows; the WiX tools are downloaded automatically.

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

- [`ui/theme/Color.kt`](shared/src/commonMain/kotlin/com/example/ui/theme/Color.kt): accent, text and call-button colors.
- [`ui/theme/Glass.kt`](shared/src/commonMain/kotlin/com/example/ui/theme/Glass.kt): glass transparency, edge brightness, and the backdrop gradient and glow colors (`LightGlassColors`, `DarkGlassColors`).

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

## Access control (admin approval)

Nobody can use the app without the admin's approval. It works like a monthly subscription: an approval is valid for 30 days, then the app locks itself until the admin approves it again. The admin manages everything from the **Admin** tab in the app, or from the Firebase console.

### How it works

1. On first start the app signs in to Firebase anonymously. Every install gets its own ID, shown on screen as **Device ID**. No password or OTP is needed.
2. The user enters a name and phone number and taps **Send request**. The app creates `devices/{uid}` in Cloud Firestore with `approved: false` and shows **Waiting for approval**. The dialer does not open.
3. The admin's phone gets a **New access request** notification. The admin taps **Approve 30 days**.
4. Within a second the user's app opens the dialer and registers the SIP account.
5. After 30 days the app locks again (**Access disabled**), unregisters SIP and sets `approved` back to `false`. The admin approves again for another 30 days, or extends the date at any time.

| Record | App |
|---|---|
| No record | Request form |
| `approved: false`, never approved | Waiting for approval |
| `approved: true`, `expiresAt` in the future | App works |
| `approved: true`, no `expiresAt` | App works and sets `expiresAt` = today + 30 days itself |
| `approved: true`, `expiresAt` passed | **Access disabled**; the app sets `approved: false` |
| `approved: false` after being approved | **Access disabled** (blocked) |

Three days before `expiresAt` the app shows a reminder on start. A blocked or expired user can tap **Check again** after the admin renews.

### Admin tab (manage from a phone)

Sign in once with the admin account: **Settings → Admin → Sign in**. The **Admin sign in** link on the approval screen does the same. The **Admin** tab then appears in the bottom bar; agents never see it. A phone signed in as admin is always allowed and needs no approval.

- The list shows every device with name, phone number, phone model, status (**Pending**, **Active**, **Expired**, **Off**) and days left. Filters: All, Pending, Active, Off.
- **Approve 30 days** for a pending, expired or blocked device.
- **+30 days** and **Block** for an active device.
- The **⋮** menu offers **+90 days**, **+1 year**, **No time limit** (valid until 2099) and **Delete**.
- A notification arrives for each new request while the admin phone is running the app. Tapping it opens the Admin tab.
- The sign-out icon at the top leaves admin mode. The phone then needs a normal access request.

The admin email is set in `LicenseManager.ADMIN_EMAIL` and in `isAdmin()` in [`firestore.rules`](firestore.rules). Change both to use another admin account. The password is not in the code. It lives only in Firebase Authentication.

### Security

The rules in [`firestore.rules`](firestore.rules) make sure that:

- only the admin account (email/password sign-in) can list, approve, renew, block and delete records;
- an install can read only its own record, and can create it only for itself, with `approved: false` and only the fields `name`, `phone`, `device`, `approved` and `createdAt`;
- the app can make exactly two changes to its own record: set `expiresAt` to 29–30 days from now right after approval, and set `approved: false` once `expiresAt` has passed. Nobody can approve themselves or extend their own date;
- once a record is expired, or switched off while it holds a SIP password, the app cannot read it at all.

`approved` is a boolean, so the Firebase console shows a **true / false** dropdown and there is nothing to mistype.

The check runs in the app, so a modified APK could skip it. For full protection, give agents their SIP account through the record (below) instead of telling them the password. Then a blocked or modified app has no SIP account to use.

### One-time Firebase setup

In the [Firebase console](https://console.firebase.google.com), project `fir-7eb9d`:

1. **Authentication → Sign-in method → Add new provider → Anonymous → Enable → Save.** Leave *Auto clean-up* off, otherwise approved installs lose their ID after 30 days.
2. **Authentication → Sign-in method → Add new provider → Email/Password → Enable → Save.** Leave *Email link* off.
3. **Authentication → Users → Add user** with the admin email and a strong password.
4. **Firestore Database → Create database.** Choose location `asia-south1` (Mumbai) and production mode.
5. **Firestore Database → Rules:** paste the contents of [`firestore.rules`](firestore.rules) and click **Publish**.

The free Spark plan is enough; no Cloud Functions are needed.

### Managing records in the Firebase console

Everything in the Admin tab can also be done in **Firestore Database → Data → devices**. Each request is one document whose ID starts with the Device ID shown in the app.

| Task | What to change |
|---|---|
| Approve for 30 days | `approved` → `true` (leave `expiresAt` out; the app adds it) |
| Block | `approved` → `false` |
| Set or extend the date | `expiresAt`, type **timestamp** |
| No time limit | `expiresAt` far in the future, e.g. 2099 |

**Give the SIP account from Firebase (optional).** Add these fields to the approved record. The app saves the account, makes it active and registers. It deletes it again when access ends.

| Field | Type | Example |
|---|---|---|
| `sipUsername` | string | `09678771660` |
| `sipPassword` | string | the SIP password |
| `sipDomain` | string | `203.76.101.50` |
| `sipPort` | number | `5060` (optional) |
| `sipDisplayName` | string | optional |

**Reinstalling** the app creates a new Device ID, so the user has to send a new request. Updating the app keeps the ID.

### Installing without a cable

Build the release APK (see [Signing](#signing)) and send the file to the phone as a **document** through WhatsApp, Telegram *Saved Messages* or Google Drive. Open it on the phone, allow *Install unknown apps* for that app, and choose **Install anyway** if Play Protect warns. Install it over the old app as an update; uninstalling first creates a new Device ID.

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

The release APK is about **32 MB** (it was about 150 MB):

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
- **Admin tab shows "Cannot load requests".** Sign in with the exact email in `firestore.rules`, and check that those rules are published and Email/Password sign-in is enabled.
- **App shows "Access disabled" although the record says approved.** `approved` must be a **boolean** `true`, not the text `"true"`. If `expiresAt` is set, it must be a **timestamp** in the future. Check that the rules from `firestore.rules` are published. Then tap **Check again**.
- **Registration fails.** The Settings screen shows the SIP error code: 401/407 for wrong credentials, 403 for a forbidden or blocked account, 404 for an unknown user, 408 for an unreachable server.
