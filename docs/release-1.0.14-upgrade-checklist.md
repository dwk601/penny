# Penny v1.0.14 release and upgrade checklist

This checklist is the release gate for the `Penny` app while preserving the
load-bearing Android package/database identity `com.dwk.flowmoney` / `flowmoney`.
It covers a replacement upgrade from archived v1.0.13 to v1.0.14. It does not
authorize a version bump by this workstream.

## Non-negotiable stop rules

- [ ] **Replacement only:** install v1.0.14 over v1.0.13 with exactly
  `adb -s SERIAL install -r CANDIDATE.apk`. Never uninstall Penny, run
  `pm clear`, clear storage in Settings, use `-d`, or fall back to a fresh
  install.
- [ ] Stop immediately if `adb install -r` fails or does not print `Success`.
  Preserve logs/snapshots and diagnose the package, version, or signing issue;
  do not “fix” it by uninstalling.
- [ ] Stop on a package, signing-certificate SHA-256, version, UID, SQLite
  `quick_check`, canonical fingerprint, preference, credential-presence, or
  widget-binding mismatch.
- [ ] Never put a SimpleFIN setup token/access URL on a command line, in a shell
  history, in release notes, in screenshots, or in logs. Enter a setup token
  only in Penny's UI.
- [ ] Treat every run-as snapshot as sensitive financial data. Keep its parent
  mode `0700`, files mode `0600`, store it on encrypted media, restrict access,
  and delete it according to the release retention policy.
- [ ] Before any phone upgrade, make the best available device-level snapshot
  and a Penny CSV export. `allowBackup=false` is intentional, so neither is a
  substitute for a tested replacement install.

## Contract and practical assumptions

The rehearsal script encodes these v1.0.13 contracts, inspected in source:

| Contract | Expected value |
| --- | --- |
| Application/package/namespace | `com.dwk.flowmoney` |
| Archived version | `versionName=1.0.13`, `versionCode=113` |
| Candidate version | `versionName=1.0.14`, `versionCode=114` |
| Room database | `databases/flow_money.db`, `PRAGMA user_version=6` |
| Transaction table | `transactions` |
| SimpleFIN tables | `simplefin_profile`, `simplefin_accounts`, `simplefin_ignored_transactions` |
| Legacy preferences | `shared_prefs/flow_money.xml`: `transactions_csv`, `room_migrated` |
| Cleanup preferences | `shared_prefs/simplefin_migration_cleanup.xml`: `v5_disconnection_complete` |
| Credential ciphertext | `no_backup/simplefin_access_url.bin` |
| Android Keystore alias | `flowmoney_simplefin_access_url` |
| Widget provider | `com.dwk.flowmoney.PennyWidgetProvider` |
| Widget routes | body → Overview; plus button → Add transaction |

Additional assumptions and limitations:

- The archived release is a debuggable, hand-archived APK, so `run-as` works.
- `adb`, `apkanalyzer`, `apksigner`, and host `sqlite3` are installed. The script
  accepts environment overrides and searches Android SDK locations, including
  `local.properties`, command-line tools, platform-tools, and the newest found
  build-tools directory.
- Use a standard Android Emulator/AVD running as Android user 0. The script
  requires an explicit `emulator-*` serial plus positive QEMU properties and
  rejects physical devices.
- Generic ADB has no portable widget-host allocation/binding command. Widget
  placement and the two taps are operator actions because launchers differ;
  the script fails unless exactly one binding is detected, the binding survives
  replacement, Penny is foregrounded, the expected UI is present, and the
  operator explicitly attests each route.
- A SimpleFIN credential is optional for the synthetic rehearsal because
  profile/account/ignored rows are seeded independently. To exercise a real
  credential, pass `--with-simplefin-credential` and connect inside Penny.
- `simplefin_access_url.bin` is AES/GCM ciphertext tied to an Android Keystore
  key on that app installation. Copying the blob, `no_backup/`, or even all
  run-as files does **not** copy the Keystore key and is not an independent
  restore path. Same-signature `adb install -r` preserves the app identity and
  key; uninstalling destroys the premise of the test.
- DB/WAL/SHM and all requested directories are captured when present. A missing
  WAL/SHM, `files/`, `no_backup/`, or credential is recorded explicitly rather
  than fabricated. WorkManager files
  under `no_backup/` may change as scheduler bookkeeping; they remain in raw
  evidence but are intentionally excluded from the user-data fingerprint. The
  credential ciphertext itself is compared exactly when present.

## 1. Release artifacts and baseline gates

- [ ] Worktree is clean except for intended release work; review
  `git status --short` and `git diff --check`.
- [ ] Confirm no package/database/preference/widget/SimpleFIN contract changed
  without an explicit migration and updated rehearsal expectation.
- [ ] Run the baseline local and build gates:

  ```bash
  ./gradlew testDebugUnitTest
  ./gradlew lintDebug assembleDebug
  ```

- [ ] On a running supported emulator/device, run the full instrumented suite:

  ```bash
  ./gradlew connectedDebugAndroidTest
  ```

- [ ] Run the focused SimpleFIN lifecycle suite and retain results:

  ```bash
  ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.dwk.flowmoney.SimpleFinLifecycleTest
  ```

- [ ] Repeat all three commands above for the final release commit. A prior run
  on an earlier commit is not a final gate.
- [ ] Build/obtain v1.0.14 without overwriting the archived v1.0.13 APK.
- [ ] Archive both immutable APKs with descriptive names under controlled
  storage. Record each file SHA-256 separately from the signing certificate
  digest.
- [ ] Use `apkanalyzer` to confirm package/version and `apksigner verify
  --print-certs` to confirm one signer and the same certificate SHA-256 on both
  APKs. Do not proceed on mismatch.

## 2. Manual final UI matrix

Run this matrix on API 26 and at least one API 31+ image. Include the current
release target image when it is newer. API 31+ is required to cover dynamic
color; API 26 covers the minimum SDK.

### Display and window matrix

- [ ] Font scales: `1.0`, `1.3`, and `2.0` (also spot-check `0.85` if available).
  Verify no clipped values/actions, unusable fields, or hidden widget content.
- [ ] Light theme and dark theme on each API level.
- [ ] API 31+: dynamic color enabled in light and dark wallpaper schemes.
  Also disable dynamic color/change to a non-dynamic image when practical to
  check Penny's fallback schemes.
- [ ] Compact phone width (`<600dp`) and wide/tablet or resized-emulator width
  (`>=600dp`); verify bottom navigation versus navigation rail behavior.
- [ ] Portrait and landscape; rotate while on Overview, Transactions, Insights,
  the data sheet, and the transaction editor.
- [ ] Show/hide the software keyboard in merchant, note, amount/date/time, CSV,
  and SimpleFIN flows. Verify IME actions, scrolling, insets, and no covered
  primary action.
- [ ] Enter/exit split-screen at narrow and wide widths. Resize across the
  navigation breakpoint with no crash, lost editor state, or inaccessible UI.

### Accessibility and restoration

- [ ] TalkBack: traverse tabs, top actions, charts/summary values, filters,
  transaction rows and custom delete action, dialogs, editor controls,
  SimpleFIN controls, and widget plus button. Labels, roles, selected states,
  state descriptions, focus order, announcements, and touch targets are clear.
- [ ] Verify color is not the only indicator for income/expense, selection,
  error, sync, or destructive actions in every theme.
- [ ] Background/foreground from every top-level tab and an open editor.
- [ ] Rotate and resize with a dirty new/edit transaction draft. Verify the
  discard/keep-editing behavior and that no transaction is silently added.
- [ ] Trigger process recreation from Developer options (“Don't keep
  activities”) or a controlled process kill while backgrounded. Verify saved UI
  state restores where promised and Room data remains intact.
- [ ] Exercise date/time pickers, import/export document pickers, permission
  denial, empty state, populated state, filters, undo delete, recurring values,
  and large/negative/positive amounts.
- [ ] Exercise SimpleFIN disconnected, connected, paused/reconnect-required,
  manual sync, cadence change, and failure UI without exposing a credential.

### Widget visual and route evidence

- [ ] Place the Penny 2x2 widget with current-month seeded spending visible.
- [ ] Capture named baseline and final screenshots at minimum for normal font,
  large font (`1.3`), light, dark, and API 31+ dynamic color. Keep the widget at
  the same launcher grid position and size for comparisons.
- [ ] Verify amount, transaction count, top category/support text, no clipping,
  48dp plus target, contrast, and update after adding/deleting a transaction.
- [ ] Tap widget body from home: Penny opens/reuses `MainActivity` and selects
  Overview, including when another tab was active.
- [ ] Tap widget plus: a fresh Add transaction sheet opens. Repeat while a dirty
  editor exists and verify Keep editing/Discard behavior before accepting the
  second route.
- [ ] Confirm body and plus remain distinct after process death, activity reuse,
  rotation, and the v1.0.13 → v1.0.14 replacement.

## 3. Emulator upgrade rehearsal

### Preflight

- [ ] Restore/create a disposable, fresh AVD; do not repurpose an emulator with
  an existing `com.dwk.flowmoney` install.
- [ ] Boot completely, unlock, select Android user 0, and make an AVD snapshot.
- [ ] Confirm the archived APK is truly v1.0.13/113 and the candidate is
  v1.0.14/114. Confirm one matching signer certificate SHA-256.
- [ ] Verify help and read-only resolution without touching a device:

  ```bash
  scripts/verify-upgrade.sh --help
  scripts/verify-upgrade.sh --dry-run
  ```

- [ ] Run a complete read-only preflight with the actual serial/APKs:

  ```bash
  scripts/verify-upgrade.sh \
    --dry-run \
    --serial emulator-5554 \
    --old-apk /secure/archive/Penny-v1.0.13-debug.apk \
    --new-apk /secure/candidate/Penny-v1.0.14-debug.apk
  ```

### Rehearsal

Use the credential option only when a disposable real SimpleFIN connection is
available. The script never accepts the setup token/access URL.

```bash
scripts/verify-upgrade.sh \
  --serial emulator-5554 \
  --old-apk /secure/archive/Penny-v1.0.13-debug.apk \
  --new-apk /secure/candidate/Penny-v1.0.14-debug.apk \
  --output-dir captures/penny-1.0.13-to-1.0.14

# Optional credential-preservation variant:
scripts/verify-upgrade.sh \
  --serial emulator-5554 \
  --old-apk /secure/archive/Penny-v1.0.13-debug.apk \
  --new-apk /secure/candidate/Penny-v1.0.14-debug.apk \
  --with-simplefin-credential
```

The script must pass all of these; do not hand-waive a failed gate:

- [ ] Explicit emulator/QEMU/user-0 checks and absent installed package.
- [ ] APK package, exact version transition, one certificate, matching
  certificate SHA-256, and candidate code greater than baseline.
- [ ] Fresh archived install and successful `run-as` startup snapshot.
- [ ] Three representative transaction rows (local expense, local income,
  SimpleFIN expense), both preference files/all existing keys, one SimpleFIN
  profile, account marker, and ignored-transaction marker.
- [ ] Profile intentionally paused during rehearsal to avoid an external sync
  mutating deterministic seed data.
- [ ] Exactly one launcher-bound widget with a captured baseline screenshot.
- [ ] Secure baseline snapshots of DB/WAL/SHM (presence or explicit absence),
  both XML preferences, `files/`, `no_backup/`, and credential ciphertext when
  present.
- [ ] Candidate install invoked only as `adb -s SERIAL install -r NEW_APK`.
- [ ] Installed base APK bytes, package, version, signer digest, and app UID
  match expectations before candidate first launch.
- [ ] Widget ID/host/provider binding is byte-for-byte canonical-equivalent
  immediately after replacement and after launch/routes.
- [ ] Widget body → Overview and plus → Add transaction, with foreground/UI
  verification, explicit operator attestation, UI dumps, and screenshots.
- [ ] Before/after host SQLite `PRAGMA quick_check` returns exactly `ok`.
- [ ] Canonical logical rows, both preference files, `files/`, encrypted
  credential presence/hash, and widget binding have the same combined
  fingerprint before/after.
- [ ] `result.txt` says `PASS`; retain `apk-preflight.txt`, installed metadata,
  run-as manifests, fingerprints, UI dumps, screenshots, and raw snapshots.

If the archived APK, candidate APK, setup token, or launcher widget automation
is unavailable, record the rehearsal as **not run**, not passed. Script
`--help`, `--dry-run`, and `bash -n` are useful implementation checks but are not
an upgrade rehearsal.

## 4. Real phone replacement preflight and snapshot

The script intentionally rejects physical devices. Execute this section
manually on the release phone only after the emulator rehearsal and final suite
pass.

- [ ] Identify the phone by an explicit adb serial; disconnect other devices.
  Record model, Android version/API, current user, battery/charging state, free
  storage, launcher, font scale, theme, dynamic-color state, and app-widget ID.
- [ ] Confirm installed Penny package is `com.dwk.flowmoney`, version is
  v1.0.13/113, and its pulled `base.apk` signer SHA-256 equals both archived and
  candidate APKs. Record the package UID.
- [ ] Confirm Penny launches and current transactions/settings/SimpleFIN status
  and widget routes are healthy before replacement. Stop if the baseline is
  already unhealthy.
- [ ] Export Penny CSV through the app and inspect counts/totals without placing
  credentials in the export/log.
- [ ] If the installed hand-release APK is debuggable, force-stop it and capture
  mode-restricted run-as copies of:
  - `databases/flow_money.db`, `flow_money.db-wal`, `flow_money.db-shm`;
  - `shared_prefs/flow_money.xml` and
    `shared_prefs/simplefin_migration_cleanup.xml`;
  - complete `files/` and `no_backup/` archives;
  - `no_backup/simplefin_access_url.bin` separately when present.
- [ ] Run host SQLite `PRAGMA quick_check` against a working copy with its
  coherent WAL/SHM sidecars. Record table counts and canonical ordered-row
  hashes, preference hashes/key values, regular `files/` hash, credential
  presence/ciphertext hash, widget ID/host/provider, package UID, and APK cert.
  Do not print row contents or any access URL.
- [ ] Remember: the run-as snapshot cannot restore the Android Keystore key.
  Never test it by uninstalling. A same-signer replacement is the only approved
  install path.
- [ ] Take screenshots of each top-level page, SimpleFIN status (with no secret),
  and widget. Take the best available encrypted phone/device snapshot.

## 5. Real phone replacement and postlaunch gates

- [ ] Force-stop Penny so DB/WAL/SHM are quiescent. Do not remove any app file.
- [ ] Run once, with the explicit phone serial:

  ```bash
  adb -s PHONE_SERIAL install -r /secure/candidate/Penny-v1.0.14-debug.apk
  ```

- [ ] If the command exits nonzero or lacks `Success`, **STOP**. Do not launch,
  retry with different flags, uninstall, clear data, or install a fresh copy.
  Preserve output and investigate from snapshots.
- [ ] Before first launch, pull installed `base.apk` and verify exact candidate
  file hash, `com.dwk.flowmoney`, v1.0.14/114, matching signer SHA-256, and the
  unchanged package UID.
- [ ] Verify the original app-widget ID remains bound to the same launcher host
  and `PennyWidgetProvider` before first launch.
- [ ] Launch Penny once. Watch for crash/ANR, initialization errors, migration
  messages, reconnect prompts, and unexpected network/sync behavior.
- [ ] Force-stop and capture the same coherent run-as set. `quick_check` must be
  `ok`; canonical business rows, preferences, regular files, credential
  presence/hash, and widget binding must match the pre-upgrade fingerprint.
  Investigate volatile Room/SQLite/WorkManager file-byte differences rather
  than comparing raw DB pages or scheduler databases as user data.
- [ ] Confirm transaction count, representative oldest/newest/local/SimpleFIN
  rows, recurring values, filters, totals, accounts, ignored transaction state,
  SimpleFIN cadence/status, and both preference keys in the UI/secure evidence.
- [ ] If a credential existed, perform a controlled SimpleFIN read/sync and
  confirm there is no reconnect-required error. Never expose the URL while
  diagnosing. If no credential existed, absence must remain absence.
- [ ] Verify widget content refresh, unchanged binding, body → Overview, and plus
  → Add transaction. Capture matching post-upgrade screenshots.
- [ ] Repeat the relevant light/dark, font, rotation, keyboard, split-screen,
  TalkBack, restoration, and dirty-editor route smoke checks on the phone.
- [ ] Keep v1.0.13 APK, v1.0.14 APK, hashes/certificate digest, suite reports,
  checklist, screenshots, and sensitive snapshots in their correct separate
  retention locations. Mark the release approved only when every required box
  is checked or an explicit release owner records a justified non-applicable
  item.
