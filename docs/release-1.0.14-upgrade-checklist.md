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
- `adb`, `apkanalyzer`, `apksigner`, host `sqlite3`, and Python 3 are installed.
  Python reads directory tar evidence without extracting it and emits the safe
  canonical file manifest. The script accepts environment overrides and
  searches Android SDK locations, including `local.properties`, command-line
  tools, platform-tools, and the newest found build-tools directory.
- Use a standard Android Emulator/AVD running as Android user 0. The script
  requires an explicit `emulator-NUMBER` serial, `ro.boot.qemu=1`, an exact
  `emulator` build characteristic, recognized ranchu/goldfish hardware, and a
  successful emulator-console AVD identity. If `ro.kernel.qemu` is present it
  must equal `1`; modern images may omit it. Physical and spoof-like devices
  fail closed.
- Generic ADB has no portable widget-host allocation/binding command. Widget
  placement and the two taps are operator actions because launchers differ;
  the script fails unless exactly one binding is detected, the binding survives
  replacement, Penny is foregrounded, and the operator explicitly attests each
  route. Before HOME/body tap, the operator must select Transactions or Insights
  and the captured hierarchy must prove exactly that non-Overview precondition;
  only a subsequent transition to exactly one selected `Overview` node passes.
  Duplicate unselected labels in seeded page content are allowed. Add must expose
  one editor subtree with exact text attributes `Add transaction`, `Close`,
  `Amount paid`, and `Save expense`. An unrelated Insights-row
  `content-desc="Edit transaction"` is allowed, while
  an Edit transaction sheet or income editor does not pass. Always-present
  nav/FAB labels are not accepted as proof.
- UI hierarchies can contain financial UI text. The script has uiautomator
  write to its inherited stdout descriptor and streams XML directly to
  mode-`0600` host artifacts without requiring a TTY. Each attempt is bounded;
  transport stderr, raw/status output, exit status, and validation diagnostics
  are retained securely. The sanitizer removes surrounding status/prompt noise
  and CR characters and validates exactly one complete hierarchy before use.
  It never stages a hierarchy in `/sdcard`, `/data/local/tmp`, or another
  device file.
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
  than fabricated. The `files.tar` snapshots remain raw evidence, but their raw
  hashes are not compared: the canonical fingerprint uses a byte-sorted JSONL
  manifest of relative path, file type, and content SHA-256. It includes empty
  directories and hashes symlink targets without extracting or following them,
  so tar order, mtime, mode, uid/gid, and header differences cannot create a
  false mismatch. Unsupported special entries fail closed. WorkManager files
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

- [ ] Run the full instrumented suite on API 26 and API 31+. Include an API 34+
  full run, which may also satisfy the API 31+ run, because the picker hierarchy
  artifact mode/cleanup subtest requires API 34+ and is suppressed below API 34:

  ```bash
  ./gradlew connectedDebugAndroidTest
  ```

  On API 34+, a picker state-assertion failure can retain a hierarchy artifact.
  It has mode `0600` in the instrumentation test APK's private storage, not
  Penny's storage, and is not part of the release fingerprint. The deliberate
  artifact mode/cleanup self-test also creates an artifact, but its `finally`
  block always removes that generated file even when a permission or location
  assertion fails; it does not provide a retained artifact for retrieval. Below
  API 34, no hierarchy artifact is written; the failed assertion reports
  `UnsupportedApi` and `Hierarchy artifact: none` instead.

  Retrieve an API 34+ failure artifact immediately, using its exact UUID-bearing
  basename from the assertion and an existing mode-`0700` host evidence
  directory. Replace the example serial, artifact basename, and destination
  directory as appropriate. The package is fixed, and the basename check must
  pass before the value is used in a device command:

  ```bash
  (
    set -eu
    umask 077
    SERIAL='emulator-5554'
    ARTIFACT='picker-hierarchy-01234567-89ab-4cde-8f01-23456789abcd.xml'
    DEST_DIR='/secure/evidence'

    if [[ ! "$ARTIFACT" =~ ^picker-hierarchy-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.xml$ ]]; then
      printf '%s\n' 'Invalid picker hierarchy artifact basename' >&2
      exit 1
    fi

    python3 - "$DEST_DIR" <<'PY'
  import os
  import stat
  import sys

  path = sys.argv[1]
  try:
      metadata = os.stat(path)
  except OSError:
      raise SystemExit("Destination evidence directory is unavailable") from None
  if not stat.S_ISDIR(metadata.st_mode):
      raise SystemExit("Destination evidence path is not a directory")
  if stat.S_IMODE(metadata.st_mode) != 0o700:
      raise SystemExit("Destination evidence directory mode is not 0700")
  PY

    adb -s "$SERIAL" shell -T run-as com.dwk.flowmoney.test \
      test -f "files/$ARTIFACT"
    REMOTE_SIZE="$(
      adb -s "$SERIAL" shell -T run-as com.dwk.flowmoney.test \
        stat -c %s "files/$ARTIFACT"
    )"
    REMOTE_SIZE="${REMOTE_SIZE%$'\r'}"
    if [[ ! "$REMOTE_SIZE" =~ ^[1-9][0-9]*$ ]]; then
      printf '%s\n' 'Invalid device artifact size' >&2
      exit 1
    fi

    DEST="$DEST_DIR/$ARTIFACT"
    test ! -e "$DEST"
    TMP="$(mktemp "${DEST}.tmp.XXXXXX")"
    trap 'rm -f -- "$TMP"' EXIT HUP INT TERM

    # exec-out is only the raw byte transport; its status is not evidence that
    # run-as or cat succeeded. The status-bearing checks above and validations
    # below establish whether retrieval succeeded.
    adb -s "$SERIAL" exec-out run-as com.dwk.flowmoney.test \
      cat "files/$ARTIFACT" >"$TMP" || :

    python3 - "$TMP" "$REMOTE_SIZE" <<'PY'
  import os
  import sys
  import xml.etree.ElementTree as ElementTree

  path = sys.argv[1]
  expected_size = int(sys.argv[2])
  if os.path.getsize(path) != expected_size:
      raise SystemExit("Artifact byte count differs from the device stat")

  try:
      root = ElementTree.parse(path).getroot()
  except (OSError, ElementTree.ParseError):
      raise SystemExit("Artifact is not one complete XML document") from None
  if root.tag != "hierarchy":
      raise SystemExit("Artifact XML root is not <hierarchy>")
  PY

    mv -- "$TMP" "$DEST"
    trap - EXIT HUP INT TERM
  )
  ```

  A picker state failure's retained on-device copy persists only until the next
  `DataOperationE2ETest` starts, when that test's setup removes stale picker
  artifacts. Retrieve it before rerunning that class. An automatically
  continuing class run may start its next test and delete the state-failure copy
  before the run returns control.

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

- [ ] Font scales: exactly `1.0`, `1.5`, and `2.0` (also spot-check `0.85` if
  available). Record the original value first. Run only one pass command at a
  time on a disposable test device, wait for configuration recreation, and
  complete the full UI pass before copying the next command. Verify app
  values/actions remain readable, fields remain usable, and primary content is
  reachable. For widgets, apply the size-specific font-scale and truncation
  gates under **Widget visual and route evidence**; expressly permitted
  truncation or omission is not a failure.

  Capture the original value once:

  ```bash
  ORIGINAL="$(adb -s SERIAL shell settings get system font_scale | tr -d '\r')"
  ```

  Complete the `1.0` pass:

  ```bash
  adb -s SERIAL shell settings put system font_scale 1.0
  ```

  Only after that pass is complete, run and complete the `1.5` pass:

  ```bash
  adb -s SERIAL shell settings put system font_scale 1.5
  ```

  Only after that pass is complete, run and complete the `2.0` pass:

  ```bash
  adb -s SERIAL shell settings put system font_scale 2.0
  ```

  Restore the setting when all passes finish. `settings get` reports an unset
  value as `null` (and some environments can produce an empty value); restore
  that state by deleting the setting rather than writing `null`/empty:

  ```bash
  if [[ -z "$ORIGINAL" || "$ORIGINAL" == "null" ]]; then
    adb -s SERIAL shell settings delete system font_scale
  else
    adb -s SERIAL shell settings put system font_scale "$ORIGINAL"
  fi
  ```

- [ ] Light theme and dark theme on each API level.
- [ ] API 31+: dynamic color enabled in light and dark wallpaper schemes.
  Also disable dynamic color/change to a non-dynamic image when practical to
  check Penny's fallback schemes.
- [ ] Exercise widths immediately below `600dp`, at `600dp`, and at `840dp`
  (plus a typical compact phone width). Below `600dp`, expect bottom navigation
  and no rail. At both `600dp` and the explicitly expanded `840dp` width, expect
  the navigation rail and no bottom navigation; verify centered/bounded page
  and editor content, insets, FAB reachability, and no stretched, clipped, or
  inaccessible controls. Capture named screenshots of Overview, Transactions,
  Insights, the data sheet, and the transaction editor at `600dp` and `840dp`
  in portrait/resizable-window form, including at least one dark or dynamic
  color variant at each width.
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

### Real widget refresh-broadcast delivery

Run every item below on API 26 and again on API 31+. Use a bound widget with a
known current-period summary and capture before/after screenshots plus system
broadcast-delivery evidence (`logcat`/`dumpsys activity broadcasts`) naming
`PennyWidgetProvider` when the image exposes it. An explicit `am broadcast` is
not proof: trigger each broadcast through the corresponding real system change.
Restore automatic time/timezone and the original locale when finished.

- [ ] `DATE_CHANGED`: with automatic date/time disabled on the disposable AVD,
  change the calendar date across a day boundary in system Settings. Confirm
  actual `android.intent.action.DATE_CHANGED` delivery and a correct, non-stale
  widget redraw (use a month boundary seed when practical).
- [ ] `TIME_SET`: change the wall-clock time within the same date in system
  Settings. Confirm actual `android.intent.action.TIME_SET` delivery and a
  successful widget redraw with unchanged correct totals.
- [ ] `TIMEZONE_CHANGED`: select a different timezone in system Settings.
  Confirm actual `android.intent.action.TIMEZONE_CHANGED` delivery and correct
  local-date/month attribution in the refreshed widget.
- [ ] `LOCALE_CHANGED`: change the system language/region in Settings. Confirm
  actual `android.intent.action.LOCALE_CHANGED` delivery and a successful
  redraw. Penny remains English by product configuration; verify readable
  English content, locale-appropriate system integration, and no crash/stale
  widget rather than expecting translated app strings.

### Widget visual and route evidence

- [ ] On API 31+, capture the widget picker preview and a screen recording or
  frame sequence of a new placement before its first provider update, using
  synthetic data only. The picker preview must show clearly sample or neutral
  aggregate content, never `$0.00` or `Unavailable`, and must not expose a
  merchant, note, account, individual transaction, or credential detail. The
  shared initial launcher frame must be neutral/loading, actionable—matching
  `Open Penny to load`—rather than a fabricated zero, stale value, or picker
  sample. It intentionally uses the compact card's `8dp` radius so it remains
  safe at the `48dp` minimum; its difference from the `28dp` radius used by the
  `2x2`/`4x2` runtime cards is expected. The initial frame must then be promptly
  replaced by the real aggregate runtime summary or the runtime `Unavailable`
  state. Retain named evidence of the picker, initial frame, and replacement
  frame within the same aggregate-only privacy boundary.
- [ ] On API 26–30, capture the launcher's widget-picker fallback and a screen
  recording or frame sequence of a new placement before its first provider
  update, using synthetic data only. Depending on the launcher, the expected
  picker fallback is Penny's app icon or the shared neutral/loading, actionable
  initial frame matching `Open Penny to load`—not the API 31+ sample preview.
  After placement, capture that initial frame. It intentionally uses the compact
  card's `8dp` radius so it remains safe at the `48dp` minimum; its difference
  from the `28dp` radius used by the `2x2`/`4x2` runtime cards is expected.
  Neither the picker fallback nor the initial frame may expose a merchant, note,
  account, individual transaction, or credential detail or present `$0.00` or
  `Unavailable` as a real state. Capture the initial frame's
  prompt replacement by the real aggregate runtime summary or the runtime
  `Unavailable` state. Retain named evidence for the picker fallback, initial
  frame, and replacement frame within the same aggregate-only privacy
  boundary.
- [ ] On API 31+, place one bound Penny widget with known current-month seeded
  spending, then use the launcher resize handles/options to exercise all three
  advertised responsive sizes: compact/short `2x1`, standard `2x2`, and wide
  `4x2`. Also drag the horizontal and vertical axes independently and then both
  axes together across the layout boundaries; do not treat placement at only
  the default `2x2` size as a pass.
- [ ] At font scale `1.0`, verify at every API 31+ size that the full `This
  month` label, correct size-appropriate amount, and distinct 48dp Add target
  remain visible, readable, and unclipped. Test at least one known month
  totaling below `$1,000` and one known month totaling at or above `$1,000`,
  calculating the expected display from the exact cents. Compact `2x1` must
  show exact dollars and cents below `$1,000`; at or above `$1,000`, it
  intentionally shows a deterministic abbreviated magnitude
  (`K`/`M`/`B`/`T`/`Q`): round half up
  to one decimal below 10 magnitude units or to a whole number otherwise,
  remove a trailing zero, and promote a rounded `1000` to the next suffix.
  Compare the displayed value with that expected rounding, and use TalkBack to
  confirm the compact widget exposes the exact, unabridged total. At `2x2`,
  verify the exact dollars-and-cents amount plus transaction total and
  top-category supporting text; at `4x2`, verify the exact dollars-and-cents
  amount, transaction total, and top-categories list. Confirm compact `2x1`
  intentionally omits supporting details rather than clipping them. Verify
  contrast and updates after adding/deleting a transaction, and confirm the
  widget exposes only aggregate spending/category information—not merchant,
  note, account, individual-transaction, or SimpleFIN credential details.
- [ ] Capture named API 31+ screenshots for `2x1`, `2x2`, `4x2`, and both-axis
  boundary resizes in day/light and night/dark dynamic-color schemes. Repeat all
  three canonical sizes at each required elevated font scale, `1.5` and `2.0`.
  At both elevated scales and every size, the complete size-appropriate visual
  amount must remain visible and readable without clipping or ellipsis, and the
  distinct 48dp Add target must remain fully visible, unobscured, and tappable.
  The full `This month` label must remain readable without clipping or ellipsis
  at `2x2` and `4x2`; at compact `2x1` only, it may end-ellipsize as needed. At
  either elevated scale, the `2x2` summary and `4x2` transaction/category
  supporting text may end-ellipsize within their own bounded regions. Permitted
  truncation must leave readable text bounded within its own region. It must not
  clip glyphs, overlap another element, or displace the amount or Add target,
  and must expose only the aggregate information allowed above. The preceding
  amount requirements still apply: exact dollars and cents at `2x2`/`4x2`, the
  specified exact/abbreviated compact visual display, and the compact TalkBack
  exact total. No visual amount may truncate or ellipsize. Include normal-font
  fallback-color evidence when dynamic color is disabled or unavailable.
- [ ] On API 26, place one bound widget, record its launcher-reported min/max
  app-widget options, and resize it horizontally, vertically, and on both axes
  through compact, standard, and wide bounds. Confirm each options change
  redraws the expected layout. Without deleting or rebinding it, rotate the
  launcher portrait → landscape → portrait at bounds that select different
  portrait and landscape layouts; screenshot each orientation and verify the
  dual `RemoteViews` switch, content, and routes rather than accepting one
  stretched or stale layout in both orientations.
- [ ] Repeat the API 26 resize/options and portrait/landscape checks in day/light
  and night/dark at font scales `1.5` and `2.0` (dynamic color is not
  applicable). Repeat both the below-`$1,000` and at-or-above-`$1,000`
  known-month cases and apply the same size-specific gates above for normal font
  and both elevated scales: compact exact/abbreviated visual amounts, expected
  rounding, compact TalkBack exact totals, standard/wide exact visual amounts,
  size-appropriate transaction total/top-category content, compact-label and
  supporting/category end-ellipsis boundaries, the intact 48dp Add target, no
  overlap, privacy, contrast, and add/delete refresh. Retain named screenshots
  for every canonical size and orientation.
- [ ] On a disposable AVD only, use synthetic data and a documented, controlled
  fault to make widget summary loading fail. Before injecting it, take a clean
  AVD snapshot and record a tested recovery method; never run this gate on a
  real phone or on an install/snapshot containing real or copied user data or
  credentials. At font scales `1.0`, `1.5`, and `2.0`, capture compact `2x1`,
  standard `2x2`, and wide `4x2`. Every size must represent the amount as
  unavailable rather than `$0.00`: standard and wide must visibly say
  `Unavailable`, and the compact size must show the short unavailable token
  `—`. At every tested font scale, each size's visible unavailable value—full
  `Unavailable` at standard/wide and `—` at compact—must remain fully visible
  and readable without clipping or ellipsis. The compact amount must be
  announced by TalkBack as the full `Unavailable`. The label, unavailable
  amount, and distinct 48dp Add target must remain bounded, separate, and
  usable. After capture, restore the known-good pre-fault snapshot or wipe and
  recreate the AVD, then verify known zero and nonzero summaries render
  correctly before using that AVD for another release gate.
- [ ] Keep the same launcher grid position and the same app-widget ID/host/provider
  binding for corresponding baseline/final comparisons on both API families.
  Responsive evidence must change size, so baseline and final screenshots are
  **not** required to use the same widget dimensions.
- [ ] Open Penny and select Transactions or Insights; capture/verify that exact
  non-Overview selection, then press HOME and tap the widget body. Penny must
  open/reuse `MainActivity` and return the selected tab to Overview. Starting on
  Overview is not route proof.
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
- [ ] Verify syntax, isolated mock regressions, help, and read-only resolution
  without touching a device:

  ```bash
  bash -n scripts/verify-upgrade.sh
  shellcheck scripts/verify-upgrade.sh
  scripts/verify-upgrade.sh --self-test
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
- [ ] APK package, exact version transition, exactly one certificate, matching
  64-hex-character certificate SHA-256 digests, and candidate code greater than
  baseline. Unsigned, multisigner, malformed-output, and verifier-error cases
  fail before digest comparison.
- [ ] Fresh archived install and successful `run-as` startup snapshot.
- [ ] Three representative transaction rows (local expense with exact persisted
  recurrence `Monthly`, local income, SimpleFIN expense), both preference
  files/all existing keys, one SimpleFIN profile, account marker, and
  ignored-transaction marker. Seed DB/preferences are streamed as unmodified
  binary host stdin over non-PTY, status-bearing `adb shell`/shell-v2 through
  `run-as` into a mode-`0600` app-private temporary. The remote write must finish,
  then `adb exec-out` independently SHA-256-verifies the temporary before a
  separate status-bearing atomic rename; the destination is verified afterward.
  No seed bytes are staged in shared device storage. Transport/temp failures
  clean only the temporary and never delete or replace an existing destination.
- [ ] Before atomically replacing `databases/flow_money.db`, the stopped app's
  existing `flow_money.db-wal` and `flow_money.db-shm` are deleted. They are
  never deleted after installing the new main DB, preventing stale sidecars from
  being associated with the replacement.
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
  verification, explicit operator attestation, UI dumps, and screenshots. Before
  HOME/body tap, a hierarchy must prove exactly one selected Transactions or
  Insights node and no selected Overview; the post-tap hierarchy must prove the
  transition to exactly one selected Overview and no selected alternate tab.
  The Add dump must contain one subtree with exact text attributes for the
  `Add transaction` editor title, `Close`, `Amount paid`, and `Save expense`, not
  merely match the persistent FAB or borrow labels from unrelated subtrees.
  After tapping plus, **do not scroll the Add editor before typing `ADD-OK`**:
  the route hierarchy check depends on visible `Amount paid`. An unrelated
  `content-desc="Edit transaction"` may coexist;
  an edit sheet still fails. Hierarchies stream directly to private host files
  and are never staged on a shared device path.
- [ ] Before/after host SQLite `PRAGMA quick_check` returns exactly `ok`.
- [ ] Canonical logical rows, both preference files, deterministic sorted
  `files/` type/content manifest, encrypted credential presence/hash, and widget
  binding have the same combined fingerprint before/after. Retain each raw
  `files.tar` only as evidence; do not compare its metadata-sensitive hash.
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
  hashes, preference hashes/key values, deterministic sorted relative-path +
  file-type/content-SHA-256 manifest for `files/` (including empty directories
  and symlinks safely), credential presence/ciphertext hash,
  widget ID/host/provider, package UID, and APK cert. Keep the raw tar as
  evidence but do not use its metadata-sensitive hash for equality.
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
