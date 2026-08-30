# FlowMoney

Android app that tracks money transactions automatically by syncing a bank
connection over SimpleFIN. Single-module, production, in daily use by its one
user (the repo owner). There is no second user, no account system, no Play
Store listing, no localization (`resourceConfigurations += listOf("en")`).

**It is not a manual ledger.** Sync is the primary model: transactions arrive
from SimpleFIN, get classified, and land in a review queue. Manual entry, the
transaction editor, and CSV import/export exist as correction and repair paths
around that pipeline — do not design features that assume the user is keeping
books by hand.

Because there is exactly one user and one device with real data on it,
irreversible data loss is the top risk in this repo. Migrations and upgrade
rehearsal are load-bearing (see skills below); a wiped install is a real loss,
not a test-fixture reset.

## Two names, both intentional

The Gradle root project and the user-facing app are `Penny`. The package,
namespace, and database are `flowmoney`. Neither is a typo. Don't normalize
one into the other, and don't rename the DB file (`flow_money.db`).

## Load-bearing gotchas

- **No DI, no version catalog, no layer packages.** Everything is flat in
  `com.dwk.flowmoney`; dependencies are constructed at use sites;
  `FlowMoneyDatabase.get(context)` is a hand-rolled double-checked singleton.
  Match that, or extract deliberately — don't half-introduce a framework.
- **`MainActivity.kt` is ~6,900 lines** and holds nearly all Compose UI. New
  screens go there unless you are deliberately extracting one.
- **AGP 9.2.1 with `android.newDsl=false` and `android.builtInKotlin=false`.**
  The opt-out is on purpose: keep the classic `android { compileOptions /
  kotlinOptions }` blocks and the standalone Kotlin plugin.
- **Material3 is pinned `strictly("1.5.0-alpha23")` on a Compose BOM alpha.**
  `ExpressiveCompat.kt` exists to absorb that churn. New expressive M3 usages
  (`MediumFlexibleTopAppBar`, `ButtonGroup`, `RichTimePickerDialog`, …) go in
  `ExpressiveCompat.kt`, not inline in `MainActivity`.
- **Untrusted bytes go through `StrictUtf8Reader`** — CSV imports and SimpleFIN
  response bodies. It enforces size caps and rejects malformed UTF-8. Route any
  new external input through it too.
- **`allowBackup=false` + `data_extraction_rules.xml` is hardening**, not
  leftover scaffolding. SimpleFIN credentials must never move to a backed-up
  location. See the `simplefin-sync` skill.
- **`FlowMoneyApplication.onCreate` blocks startup once** under `runBlocking`
  to delete a credential whose profile no longer exists
  (`SimpleFinMigrationCleanup`). The blocking call is intentional and runs at
  most once, guarded by a SharedPreferences flag.

## Tests

JUnit4 + Truth + `kotlinx-coroutines-test`. No Robolectric, no mocking
framework — if it needs Android, it runs instrumented.

ViewModel and DAO tests live in `androidTest`, not `test`
(`MainViewModelDataPathTest`, `TransactionDaoDataPathTest`, and friends).
They are logic tests that need a real Room/SQLite engine. Don't "fix" them by
moving them to `test`. Screenshot testing is not set up.

```
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
./gradlew connectedDebugAndroidTest   # needs a running emulator or device
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dwk.flowmoney.SimpleFinLifecycleTest
```

## Skills

| Skill | Load when |
| --- | --- |
| `.agents/skills/simplefin-sync/` | Touching bank sync: connect/reconnect/disconnect, credential storage, the sync worker or throttle, provider-owned columns, the review queue, merchant rules, or transfer classification. |
| `.agents/skills/room-migrations/` | Changing any `@Entity`, DAO schema, or the Room version in `FlowMoneyDatabase.kt`. |
| `.agents/skills/release-build/` | Cutting a build into `dist/`, bumping version, or running the on-device upgrade rehearsal. |

## Maintaining this file

Keep only what is useful in almost every session. When a rule belongs to one
area, put it in that skill and delete it here — a rule lives in exactly one
place. Prune stale entries instead of appending; this file has already drifted
once (it claimed schema v8 and a `.slim/deepwork/` directory that no longer
exists). If something is visible by reading the repo, it does not belong here.
