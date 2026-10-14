# Novelfa Android — Play Release Guide (Task 9)

## 0. What this release contains (release-hardening summary)

- **R8/minify ON** (`app/build.gradle.kts` release block) with keep rules in
  `app/proguard-rules.pro`: Room `data.**`, `ScrollBridge` + all
  `@JavascriptInterface` methods, `MainActivity` / `NovelfaApplication` /
  `BuildConfig`. Verified: all three classes appear in
  `app/build/outputs/mapping/release/seeds.txt` after `:app:assembleRelease`.
- **Auto Backup**: `allowBackup=true`; pre-Android-12 reads
  `res/xml/backup_rules.xml`, Android 12+ reads
  `res/xml/data_extraction_rules.xml` (identical semantics: Room DB +
  DataStore files included; `files/books/` + `files/covers/` excluded as
  re-importable/heavy; `cache/` needs no rule — never backed up).
- **Coil cover cache capped at 100 MB** (`NovelfaApplication`, registered via
  `android:name` in the manifest; `cacheDir/covers`).
- **Continuous-reader windowing**: rendered span capped at 7 chapters
  (`ReaderViewModel.MAX_WINDOW_SPAN`); overflow drops the far end and rebuilds
  with `lastChunkKind="full"`. Covered by `ReaderViewModelTest` (2 tests).
- **Rotation / process death**: `MainActivity` handles
  `orientation|screenSize|screenLayout|keyboardHidden` in place (no recreate
  on rotation). Reader `continuous` flag (rememberSaveable) + library
  tab/query (rememberSaveable) survive; the DB (`saveProgress`) is
  authoritative for `index` on restore. Safe default: a fresh entry (or death
  without saved state) opens the reader in **single-chapter mode**
  (`continuous=false`).

## 1. Version bump

In `android/app/build.gradle.kts` → `defaultConfig`:

```kotlin
versionCode = 2        // +1 on EVERY Play upload (integer, never reused)
versionName = "1.0.1"  // user-visible; e.g. 1.0.1 / 1.1.0
```

Commit the bump separately, e.g. `chore(android): bump version to 1.0.1 (vc 2)`.

## 2. Build the release artifacts

From `android/` (R8 on a 2-core/6GB machine takes several minutes; the
first release build here produced `app-release-unsigned.apk` 3.6 MB and
`app-release.aab` 4.0 MB):

```powershell
.\gradlew.bat :app:testDebugUnitTest      # full unit suite must be green (currently 117/117)
.\gradlew.bat :app:assembleRelease        # R8 APK (unsigned without a keystore)
.\gradlew.bat :app:bundleRelease          # upload artifact: app/build/outputs/bundle/release/app-release.aab
```

Smoke-check the R8 output after every release build:

- `outputs/mapping/release/seeds.txt` contains `ScrollBridge`,
  `NovelfaApplication`, `MainActivity`.
- `outputs/mapping/release/mapping.txt` exists (keep it — required to
  de-obfuscate Play crash reports; upload it under
  Play Console → App bundle explorer → Downloads if not using Play App
  Signing's automatic handling).

## 3. Signing — Play App Signing (recommended)

1. Play Console → your app → **Setup → App integrity → App signing**:
   enrol in **Play App Signing** (Google holds the app-signing key).
2. First upload: build a **signed** AAB. Either
   - let Play generate the upload key (Play Console → App integrity →
     “Use Google-generated upload key”), or
   - create your own keystore once and keep it OUT of git:
     ```powershell
     keytool -genkeypair -alias novelfa-upload -keyalg RSA -keysize 2048 `
       -validity 9125 -keystore novelfa-upload.jks
     ```
     and add a `signingConfigs.release` block reading the passwords from
     environment variables / `~/.gradle/gradle.properties` (never commit
     the `.jks` or passwords).
3. The unsigned `app-release-unsigned.apk` in `outputs/apk/release/` is a
   **local R8 smoke artifact only** — never upload an unsigned APK to Play.

## 4. Upload

1. Play Console → **Release → Production → Create new release** (or
   Internal testing first for a soak test).
2. Upload `app-release.aab`. Confirm `versionCode` incremented, `minSdk 29`,
   `targetSdk 35`.
3. Fill **Release notes** (fa + en).
4. Roll out (start with a staged rollout, e.g. 20 %, unless it is a hotfix).

## 5. Permissions & Data safety (privacy)

- The app declares **zero permissions** — EPUB import is via the Storage
  Access Framework (`OpenDocument`), which needs no
  `READ_EXTERNAL_STORAGE` on API 29+. Fully offline: no network, no
  analytics, no backend, no ads.
- Play Console → **Policy → App content → Data safety** → declare
  **“No data collected”** (and “No data shared”). If this ever changes
  (e.g. crash reporting is added), update the declaration first.
- **Privacy policy**: not required while no data is collected and no
  account system exists; add one the moment that changes.
- **App content → Content rating**: complete the questionnaire
  (a reading app with user-imported books typically rates Everyone;
  answer truthfully for your listings).
- **Target audience / News / COVID / Government** sections: answer per
  Console prompts; nothing special applies.

## 6. Store listings (fa + en)

- Default locale is **Persian (fa), RTL**; English (`en`) is the secondary
  listing. Source strings live in `values-fa/strings.xml` (+ Task 4 `Strings`
  map) and `values/strings.xml` — reuse the app name, short/long
  descriptions, and feature bullets from there so listings match in-app text.
- Required graphics: 512×512 icon, feature graphic 1024×500, ≥2 phone
  screenshots per locale (fa + en), 7-inch/10-inch tablet screenshots
  recommended.
- Category: Books & Reference.

## 7. Pre-upload checklist (manual gate)

- [ ] `:app:testDebugUnitTest` green; `assembleRelease` + `bundleRelease` green.
- [ ] R8 seeds contain `ScrollBridge` / `NovelfaApplication` / `MainActivity`.
- [ ] Import 5 real Persian EPUBs on a device; single + continuous modes OK.
- [ ] 50-chapter book in continuous mode: no ANR (windowing caps at 7).
- [ ] Rotation mid-read keeps position; “kill process → reopen” restores
      index from DB in single mode.
- [ ] Backup round-trip: books/covers NOT restored (re-import note shown),
      progress + settings restored.
- [ ] Cold start < 2 s on a mid-range device; chapter open < 500 ms.
- [ ] Data safety = “No data collected”; content rating completed.

## 8. NOT-RUN in this environment (no device/emulator here)

- Installing/launching the release APK on hardware.
- Manual gate above (§7) — must be done on a real device before the first
  production rollout; prefer Internal testing → Production promotion.
