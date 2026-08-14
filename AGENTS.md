# Penny

A single-module Android personal finance app: manual transaction entry, CSV
import/export, a dashboard, a home-screen widget, and optional bank sync over
SimpleFIN.

Two names are both load-bearing and neither is a typo — the Gradle root project
and the user-facing app are `Penny`, while the package, namespace, and database
are `flowmoney`. Don't normalize one into the other.

## Shape of the code

Everything lives flat in `com.dwk.flowmoney` — no layer packages, no module
split. `MainActivity.kt` is ~3,500 lines and holds nearly all of the Compose
UI; new screens go in it unless you are deliberately extracting.

There is no DI framework. Dependencies are constructed where they are used, and
`FlowMoneyDatabase.get(context)` is a hand-rolled double-checked singleton.
There is also no version catalog — dependencies are declared inline in
`app/build.gradle.kts`.

## Build

- AGP 9.2.1 with `android.newDsl=false` and `android.builtInKotlin=false`. The
  build opts out of AGP 9's new DSL and built-in Kotlin on purpose, so keep
  using the classic `android { compileOptions/kotlinOptions }` blocks and the
  standalone Kotlin plugin.
- Compose comes from `compose-bom-alpha`, and Material3 is pinned
  `strictly("1.5.0-alpha23")`. `ExpressiveCompat.kt` exists to absorb that
  churn — expressive M3 APIs (`MediumFlexibleTopAppBar`, `ButtonGroup`,
  `RichTimePickerDialog`) are wrapped there rather than called from
  `MainActivity`. Put new expressive usages in the same place.
- English only, by config: `resourceConfigurations += listOf("en")`.
- Releases are hand-archived debug builds. Bump `versionCode`/`versionName`,
  then copy the APK to `dist/` as `Penny-v<versionName>-<what-changed>-debug.apk`.

```
./gradlew testDebugUnitTest        # local unit tests
./gradlew lintDebug assembleDebug
./gradlew connectedDebugAndroidTest # requires a running emulator or device
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dwk.flowmoney.SimpleFinLifecycleTest
```

## Tests

No Robolectric, no mocking framework. Local tests are JUnit4 + Truth +
`kotlinx-coroutines-test`; anything that needs Android runs instrumented.

ViewModel and DAO tests sit in `androidTest`, not `test` — `MainViewModelDataPathTest`,
`TransactionDaoDataPathTest`, and friends are logic tests that need a real
Room/SQLite engine. Don't "fix" them by moving them.

Screenshot testing is not set up. `.agents/skills/testing-setup` covers adding
it, along with the rest of the Android testing stack.

## Data and credentials

- Room is at schema version 6. Migrations 1→6 are hand-written in
  `FlowMoneyDatabase.kt`, but `app/schemas/` only contains `5.json` and
  `6.json`, so automated migration tests can't reach further back than 5.
- SimpleFIN access URLs are encrypted with an AndroidKeystore AES/GCM key and
  written to `noBackupFilesDir` (`SimpleFinCredentialStore`). They must never
  be logged, and must never move to SharedPreferences or any backed-up
  location.
- `allowBackup=false` plus `data_extraction_rules.xml` is a deliberate
  hardening choice, not leftover scaffolding.
- `FlowMoneyApplication.onCreate` blocks startup once, under `runBlocking`, to
  delete a credential whose profile no longer exists
  (`SimpleFinMigrationCleanup`). The blocking call is intentional and runs at
  most once, guarded by a SharedPreferences flag.
- Untrusted bytes — CSV imports and SimpleFIN responses — go through
  `StrictUtf8Reader`, which enforces a size cap and rejects malformed UTF-8.
  Route new external input through it too.

## Notes from prior work

`.slim/deepwork/` holds write-ups from earlier tasks (SimpleFIN sync, security
remediation, Material 3 Expressive migration, widget and performance work).
They are history, not spec — useful for understanding why something looks the
way it does.
