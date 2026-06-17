# Krishna Ale CRM — Android client app

A branded Android app that wraps your AgencyHandy client portal
(`https://krishnaale.agencyhandy.com/`) and adds native features clients expect:

- **Push notifications** for new tasks, completed tasks, and new invoices
  (via AgencyHandy webhooks → your Firebase backend → the client's phone)
- **Biometric / PIN app lock** (on by default)
- **File uploads** from files or the camera, and **downloads** (e.g. invoice PDFs)
- **Pull-to-refresh**, an **offline screen** with retry, and external links
  (payments, etc.) opening in a Chrome Custom Tab
- Your branding: app name **Krishna Ale CRM**, portal colour, and your logo

Built as a native **Kotlin** app (lightest, most robust option for a WebView wrapper that
must pass Play review and handle uploads/downloads/push/biometrics reliably).

---

## Project layout

```
KrishnaAleCRM/
├─ app/                      # the Android app (Kotlin)
│  ├─ src/main/java/au/krishnaale/crm/
│  │  ├─ MainActivity.kt          # the WebView + all native behaviour
│  │  ├─ SplashActivity.kt        # branded launch + app-lock gate
│  │  ├─ AppLock.kt               # biometric / device-credential lock
│  │  ├─ SecurePrefs.kt           # encrypted storage (email, lock flag)
│  │  ├─ CrmApplication.kt        # notification channels + lifecycle
│  │  ├─ CrmMessagingService.kt   # receives pushes, shows notifications
│  │  ├─ DeviceRegistration.kt    # registers email+token with the backend
│  │  ├─ DownloadBridge.kt        # JS bridge that saves blob downloads (invoice PDFs)
│  │  └─ BlobDownloader.kt        # writes downloaded bytes to the Downloads folder
│  ├─ src/main/res/...            # layout, colours, strings, themes, icons
│  ├─ google-services.json        # PLACEHOLDER — replace (see step 2)
│  └─ build.gradle.kts
└─ backend/                  # Firebase Cloud Functions (push) — see backend/README.md
```

---

## Setup

### 1. Open in Android Studio
Open the `KrishnaAleCRM` folder in Android Studio (latest stable). It will sync Gradle and
generate the Gradle wrapper automatically. (If you build from the command line instead,
run `gradle wrapper` once first — the wrapper JAR isn't bundled.)

### 2. Connect Firebase (needed for push)
1. Create a Firebase project at <https://console.firebase.google.com>.
2. **Add app → Android**, package name exactly: `au.krishnaale.crm`.
3. Download the generated **`google-services.json`** and drop it into `app/`, replacing
   the placeholder.
4. Stand up the push backend — follow **`backend/README.md`** (uses the *same* Firebase project).

### 3. Point the app at your backend
After deploying the functions, copy the base function URL into `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "BACKEND_URL",
    "\"https://australia-southeast1-YOUR_PROJECT.cloudfunctions.net\"")
```

(Until this is set, the app still runs and shows the portal — it just won't register for push.)

### 4. Drop in your real logo and colour
- **Logo:** right-click `app/res` → **New → Image Asset** → *Launcher Icons (Adaptive and
  Legacy)* → select your logo. This regenerates the launcher icon at all sizes. Replace
  `res/drawable/ic_toolbar_logo.xml` and `res/drawable/splash_logo.xml` with your mark too
  (a vector or PNG). The current monogram is a placeholder.
- **Colour:** the palette is anchored on the portal's blue `#90CAF9` in `res/values/colors.xml`.
  If you have an exact brand hex, set `brand` (and optionally `brand_dark` / `accent`) there.

### 5. Run it
Plug in a device (or start an emulator) and press **Run**. You should see the splash, the
unlock prompt, then your portal. On first launch it asks for the email clients use to log in
(so push can be targeted) — you can Skip and set it later from the ⋮ menu.

---

## Publishing to Google Play (when you're ready)

1. **Generate an upload keystore** (keep this file safe — you'll need it for every update):
   ```bash
   keytool -genkey -v -keystore krishna-crm-release.jks -alias krishna \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Add the signing details to `~/.gradle/gradle.properties` (not in source control):
   ```properties
   KS_FILE=/absolute/path/to/krishna-crm-release.jks
   KS_STORE_PASSWORD=********
   KS_KEY_ALIAS=krishna
   KS_KEY_PASSWORD=********
   ```
3. Build the release bundle:
   ```bash
   ./gradlew bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`
4. In **Google Play Console**: create the app, complete the store listing (you'll need a
   512×512 icon and a feature graphic), upload the `.aab`, and roll out to internal testing
   first, then production.

> Heads-up for Play review: pure "website in a WebView" apps can be rejected for minimum
> functionality. This app adds real native value (push, biometric lock, file handling,
> offline, Custom Tabs) and wraps **your own** service, which is the combination reviewers
> look for. Describe those native features in the listing. When you're at this step, tell me
> and I'll walk you through the Console screens and the data-safety form.

---

## What I still need from you to make push fully live

1. **`google-services.json`** from your Firebase project (replaces the placeholder).
2. **One captured webhook payload** (via AgencyHandy's test/redeliver with
   `DEBUG_LOG_PAYLOAD=true`) so I can confirm/lock the "which client" field mapping in the
   backend.
3. **Your exact brand hex + logo file** (currently a placeholder monogram + portal blue).
4. **Confirm webhooks are available on your AgencyHandy plan** (the feature exists; just
   verifying it's enabled for your workspace).

Everything else is done and ready to build.
