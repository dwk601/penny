#!/usr/bin/env bash
# Test-only regression harness for scripts/verify-upgrade.sh.
#
# Scope: CLI surface and read-only safety properties that the script's own
# --self-test does not cover, namely
#   * exit statuses and option-validation messages driven through the real CLI,
#   * that --help/--self-test/--dry-run never invoke adb at all,
#   * a fully mocked end-to-end --dry-run (no device, no emulator, no APK),
#   * that an already-installed package fails closed without uninstall/clear.
#
# This harness never starts an interactive rehearsal, never installs, never
# uninstalls, and never clears app data. Every invocation of the script under
# test carries --help, --self-test, or --dry-run, and stdin is /dev/null.

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_UNDER_TEST="$(cd "$TESTS_DIR/.." && pwd)/verify-upgrade.sh"

PASS_COUNT=0
FAIL_COUNT=0
WORK_DIR=""

cleanup() {
	[[ -z "$WORK_DIR" ]] || rm -rf "$WORK_DIR"
}
trap cleanup EXIT

pass() {
	printf '[cli-test] PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf '[cli-test] FAIL: %s\n' "$1" >&2
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

# Run the script under test with stdin closed. Never interactive.
run_script() {
	"$SCRIPT_UNDER_TEST" "$@" </dev/null 2>&1
}

expect_status_and_text() {
	local name="$1"
	local expected_status="$2"
	local expected_text="$3"
	shift 3
	local output status
	if output="$(run_script "$@")"; then
		status=0
	else
		status=$?
	fi
	if [[ "$expected_status" == "nonzero" ]]; then
		[[ $status -ne 0 ]] || {
			fail "$name (expected nonzero exit, got 0)"
			return 0
		}
	elif [[ "$status" != "$expected_status" ]]; then
		fail "$name (expected exit $expected_status, got $status)"
		return 0
	fi
	if [[ -n "$expected_text" ]] && ! printf '%s\n' "$output" | grep -Fq -- "$expected_text"; then
		fail "$name (output lacked: $expected_text)"
		return 0
	fi
	pass "$name"
}

make_mock_tools() {
	local bin="$1"
	local pm_path_status="$2"
	mkdir -p "$bin"

	cat >"$bin/adb" <<SH
#!/bin/sh
printf '%s\n' "\$*" >>"\$ADB_LOG"
shift 2 # drop -s SERIAL
case "\$*" in
"get-state") echo device ;;
"shell getprop ro.kernel.qemu") echo "" ;;
"shell getprop ro.boot.qemu") echo 1 ;;
"shell getprop ro.build.characteristics") echo emulator ;;
"shell getprop ro.hardware") echo ranchu ;;
"shell getprop ro.boot.hardware") echo ranchu ;;
"emu avd name") printf 'Penny_Test_AVD\nOK\n' ;;
"shell am get-current-user") echo 0 ;;
"shell pm path com.dwk.flowmoney")
	if [ "$pm_path_status" -eq 0 ]; then
		echo "package:/data/app/com.dwk.flowmoney/base.apk"
	fi
	exit $pm_path_status
	;;
*)
	echo "MOCK-ADB-UNEXPECTED: \$*" >&2
	exit 97
	;;
esac
SH

	cat >"$bin/apkanalyzer" <<'SH'
#!/bin/sh
base=$(basename "$3")
case "$2" in
application-id) echo com.dwk.flowmoney ;;
version-name) case "$base" in old*) echo 1.0.13 ;; *) echo 1.0.14 ;; esac ;;
version-code) case "$base" in old*) echo 113 ;; *) echo 114 ;; esac ;;
*) exit 3 ;;
esac
SH

	cat >"$bin/apksigner" <<'SH'
#!/bin/sh
echo "Signer #1 certificate SHA-256 digest: aaaabbbbccccddddeeeeffff00001111aaaabbbbccccddddeeeeffff0000111a"
SH

	chmod 755 "$bin/adb" "$bin/apkanalyzer" "$bin/apksigner"
}

# --- static guards -----------------------------------------------------------

test_static() {
	if bash -n "$SCRIPT_UNDER_TEST"; then
		pass "bash -n parses verify-upgrade.sh"
	else
		fail "bash -n rejected verify-upgrade.sh"
	fi

	if command -v shellcheck >/dev/null 2>&1; then
		if shellcheck -x "$SCRIPT_UNDER_TEST"; then
			pass "shellcheck reports no findings"
		else
			fail "shellcheck reported findings"
		fi
	else
		printf '[cli-test] SKIP: shellcheck is not installed\n'
	fi

	# shellcheck disable=SC2016  # literal script text, expansion is not wanted
	if grep -nE '(^|[^-[:alnum:]])(uninstall|pm[[:space:]]+clear|cmd[[:space:]]+package[[:space:]]+uninstall)' \
		"$SCRIPT_UNDER_TEST" | grep -vE 'never|not |no |do not|Do not|refus|forbid|without' >/dev/null; then
		fail "verify-upgrade.sh contains an executable uninstall/clear-data operation"
	else
		pass "verify-upgrade.sh has no executable uninstall or clear-data operation"
	fi

	local install_sites replace_sites
	# shellcheck disable=SC2016  # literal script text, expansion is not wanted
	install_sites="$(grep -cE 'install (-r )?"\$(OLD|NEW)_APK"' "$SCRIPT_UNDER_TEST")"
	# shellcheck disable=SC2016  # literal script text, expansion is not wanted
	replace_sites="$(grep -cE 'install -r "\$NEW_APK"' "$SCRIPT_UNDER_TEST")"
	if [[ "$install_sites" == "2" && "$replace_sites" == "1" ]]; then
		pass "exactly two install call sites exist (archived fresh install, candidate install -r)"
	else
		fail "unexpected install call sites (total=$install_sites, install -r=$replace_sites)"
	fi
}

# --- help and option validation ----------------------------------------------

test_help_and_options() {
	local help_text
	help_text="$(run_script --help)"

	expect_status_and_text "--help exits 0" 0 "Usage:" --help
	expect_status_and_text "--help works after other options" 0 "Usage:" \
		--serial emulator-5554 --help

	if printf '%s\n' "$help_text" | grep -Fq -- '--pre-db-user-version N' &&
		printf '%s\n' "$help_text" | grep -Fq -- '--post-db-user-version N' &&
		printf '%s\n' "$help_text" | grep -Fq 'default: 10' &&
		printf '%s\n' "$help_text" | grep -Fq 'default: 11'; then
		pass "--help documents distinct pre/post DB user versions with defaults 10 and 11"
	else
		fail "--help does not document distinct pre/post DB user version defaults"
	fi

	if printf '%s\n' "$help_text" | grep -Eq -- '^[[:space:]]*--db-user-version'; then
		fail "--help still advertises the ambiguous single --db-user-version option"
	else
		pass "--help does not advertise the ambiguous single --db-user-version option"
	fi

	expect_status_and_text "ambiguous --db-user-version is rejected with a value" nonzero \
		'--db-user-version is not accepted' --db-user-version 11 --dry-run
	expect_status_and_text "ambiguous --db-user-version is rejected without a value" nonzero \
		'--db-user-version is not accepted' --db-user-version
	expect_status_and_text "unknown option is rejected" nonzero \
		'Unknown option: --bogus' --bogus
	expect_status_and_text "--option=value form is rejected rather than silently ignored" nonzero \
		'Unknown option: --pre-db-user-version=10' --pre-db-user-version=10 --dry-run

	expect_status_and_text "missing --serial value is rejected" nonzero \
		'--serial requires a value' --serial
	expect_status_and_text "missing --old-apk value is rejected" nonzero \
		'--old-apk requires a value' --old-apk
	expect_status_and_text "missing --new-apk value is rejected" nonzero \
		'--new-apk requires a value' --new-apk
	expect_status_and_text "missing --output-dir value is rejected" nonzero \
		'--output-dir requires a value' --output-dir
	expect_status_and_text "missing --pre-db-user-version value is rejected" nonzero \
		'--pre-db-user-version requires a value' --pre-db-user-version
	expect_status_and_text "missing --post-db-user-version value is rejected" nonzero \
		'--post-db-user-version requires a value' --post-db-user-version

	expect_status_and_text "non-integer --pre-db-user-version is rejected" nonzero \
		'--pre-db-user-version must be an integer' --pre-db-user-version abc --dry-run
	expect_status_and_text "non-integer --post-db-user-version is rejected" nonzero \
		'--post-db-user-version must be an integer' --post-db-user-version 11.0 --dry-run
	expect_status_and_text "negative --pre-db-user-version is rejected" nonzero \
		'--pre-db-user-version must be an integer' --pre-db-user-version -1 --dry-run
	expect_status_and_text "equal pre/post DB user versions are rejected" nonzero \
		'candidate DB user_version must be greater than archived DB user_version' \
		--pre-db-user-version 11 --post-db-user-version 11 --dry-run
	expect_status_and_text "reversed pre/post DB user versions are rejected" nonzero \
		'candidate DB user_version must be greater than archived DB user_version' \
		--pre-db-user-version 11 --post-db-user-version 10 --dry-run

	expect_status_and_text "non-integer --old-version-code is rejected" nonzero \
		'--old-version-code must be an integer' --old-version-code x --dry-run
	expect_status_and_text "non-increasing versionCode is rejected" nonzero \
		'candidate versionCode must be greater than archived versionCode' \
		--old-version-code 114 --new-version-code 114 --dry-run

	expect_status_and_text "--dry-run with only --serial still requires --old-apk" nonzero \
		'--old-apk is required' --dry-run --serial emulator-5554
	expect_status_and_text "--dry-run with only --old-apk still requires --serial" nonzero \
		'--serial is required' --dry-run --old-apk /nonexistent/old.apk
}

# --- no device contact in non-rehearsal modes --------------------------------

test_no_device_contact() {
	local bin="$WORK_DIR/recorder-bin"
	local log="$WORK_DIR/recorder.log"
	local mode status
	mkdir -p "$bin"
	for tool in adb apkanalyzer apksigner; do
		# shellcheck disable=SC2016  # $* and $ADB_LOG must reach the generated mock
		printf '#!/bin/sh\nprintf "%%s %%s\\n" "%s" "$*" >>"$ADB_LOG"\nexit 1\n' "$tool" >"$bin/$tool"
		chmod 755 "$bin/$tool"
	done

	for mode in --help --self-test --dry-run; do
		: >"$log"
		if ADB_LOG="$log" ADB="$bin/adb" APKANALYZER="$bin/apkanalyzer" APKSIGNER="$bin/apksigner" \
			run_script "$mode" >/dev/null; then
			status=0
		else
			status=$?
		fi
		if [[ $status -eq 0 && ! -s "$log" ]]; then
			pass "$mode exits 0 and invokes no adb/apkanalyzer/apksigner process"
		else
			fail "$mode exited $status and/or invoked tools: $(tr '\n' ';' <"$log")"
		fi
	done

	if [[ ! -e "$(dirname "$SCRIPT_UNDER_TEST")/../captures" ]]; then
		pass "non-rehearsal modes create no captures/ output directory"
	else
		fail "non-rehearsal modes created a captures/ output directory"
	fi
}

# --- fully mocked --dry-run ---------------------------------------------------

test_mocked_dry_run() {
	local bin="$WORK_DIR/dryrun-bin"
	local log="$WORK_DIR/dryrun-adb.log"
	local old_apk="$WORK_DIR/old-Penny.apk"
	local new_apk="$WORK_DIR/new-Penny.apk"
	local output status
	make_mock_tools "$bin" 1
	printf 'archived\n' >"$old_apk"
	printf 'candidate\n' >"$new_apk"
	: >"$log"

	if output="$(ADB_LOG="$log" ADB="$bin/adb" APKANALYZER="$bin/apkanalyzer" APKSIGNER="$bin/apksigner" \
		run_script --dry-run --serial emulator-5554 --old-apk "$old_apk" --new-apk "$new_apk")"; then
		status=0
	else
		status=$?
	fi

	if [[ $status -eq 0 ]] &&
		printf '%s\n' "$output" | grep -Fq 'Read-only dry run passed' &&
		printf '%s\n' "$output" | grep -Fq 'Positively identified emulator emulator-5554' &&
		printf '%s\n' "$output" | grep -Fq 'APK package/version transition verified'; then
		pass "mocked --dry-run completes read-only preflight and exits 0"
	else
		fail "mocked --dry-run did not complete (exit $status)"
		printf '%s\n' "$output" >&2
	fi

	if ! grep -Eq '(^| )(install|uninstall)( |$)|pm clear|force-stop|screencap|input keyevent|am start|exec-out|run-as' "$log"; then
		pass "mocked --dry-run issues no install/uninstall/clear/launch/capture adb command"
	else
		fail "mocked --dry-run issued a mutating adb command: $(grep -nE 'install|uninstall|pm clear|force-stop|screencap|input keyevent|am start|exec-out|run-as' "$log" | tr '\n' ';')"
	fi

	if grep -Fq 'shell pm path com.dwk.flowmoney' "$log"; then
		pass "mocked --dry-run checks for a pre-existing installation"
	else
		fail "mocked --dry-run never checked for a pre-existing installation"
	fi

	# The printed plan must reflect the explicit pre/post DB user versions.
	if output="$(ADB_LOG="$log" ADB="$bin/adb" APKANALYZER="$bin/apkanalyzer" APKSIGNER="$bin/apksigner" \
		run_script --dry-run --serial emulator-5554 --old-apk "$old_apk" --new-apk "$new_apk" \
		--pre-db-user-version 7 --post-db-user-version 9)" &&
		printf '%s\n' "$output" | grep -Fq 'seed deterministic v7 rows' &&
		printf '%s\n' "$output" | grep -Fq '7->9 location schema/backfill/completion deltas'; then
		pass "explicit --pre/--post DB user versions propagate into the printed plan"
	else
		fail "explicit --pre/--post DB user versions did not propagate into the printed plan"
	fi

	# Version mismatches must fail closed before any device mutation.
	if output="$(ADB_LOG="$log" ADB="$bin/adb" APKANALYZER="$bin/apkanalyzer" APKSIGNER="$bin/apksigner" \
		run_script --dry-run --serial emulator-5554 --old-apk "$old_apk" --new-apk "$new_apk" \
		--old-version-code 113 --new-version-code 999)"; then
		status=0
	else
		status=$?
	fi
	if [[ $status -ne 0 ]] &&
		printf '%s\n' "$output" | grep -Fq 'Candidate versionCode is 114, expected 999'; then
		pass "candidate versionCode mismatch fails closed in --dry-run"
	else
		fail "candidate versionCode mismatch did not fail closed in --dry-run (exit $status)"
	fi
}

test_mocked_already_installed() {
	local bin="$WORK_DIR/installed-bin"
	local log="$WORK_DIR/installed-adb.log"
	local old_apk="$WORK_DIR/old-Penny.apk"
	local new_apk="$WORK_DIR/new-Penny.apk"
	local output status
	make_mock_tools "$bin" 0
	: >"$log"

	if output="$(ADB_LOG="$log" ADB="$bin/adb" APKANALYZER="$bin/apkanalyzer" APKSIGNER="$bin/apksigner" \
		run_script --dry-run --serial emulator-5554 --old-apk "$old_apk" --new-apk "$new_apk")"; then
		status=0
	else
		status=$?
	fi

	if [[ $status -ne 0 ]] &&
		printf '%s\n' "$output" | grep -Fq 'is already installed on emulator-5554' &&
		printf '%s\n' "$output" | grep -Fq 'do not uninstall or clear data'; then
		pass "an already-installed package fails closed with a restore-snapshot instruction"
	else
		fail "an already-installed package did not fail closed (exit $status)"
	fi

	if ! grep -Eq 'uninstall|pm clear' "$log"; then
		pass "the already-installed refusal issues no uninstall or clear-data command"
	else
		fail "the already-installed refusal issued an uninstall/clear-data command"
	fi
}

# --- embedded self-test coverage ---------------------------------------------

test_self_test_coverage() {
	local output status expected missing=""
	if output="$(run_script --self-test)"; then
		status=0
	else
		status=$?
	fi

	if [[ $status -eq 0 ]] && printf '%s\n' "$output" | grep -Fq 'All mock self-tests passed'; then
		pass "--self-test exits 0 with all mock self-tests passing"
	else
		fail "--self-test failed (exit $status)"
		printf '%s\n' "$output" | grep -F 'SELF-TEST FAIL' >&2 || true
	fi

	if printf '%s\n' "$output" | grep -Fq 'SELF-TEST FAIL'; then
		fail "--self-test reported at least one failing case"
	else
		pass "--self-test reported no failing case"
	fi

	# The 10->11 accept/reject matrix the upgrade verification depends on.
	for expected in \
		"delta logic accepts expected-10-11" \
		"delta logic rejects extra-column" \
		"delta logic rejects extra-table" \
		"delta logic rejects logical-tamper" \
		"delta logic rejects missing-backfill" \
		"delta logic rejects unparseable-located" \
		"delta logic rejects local-located" \
		"delta logic rejects inferred-country" \
		"delta logic rejects missing-completion" \
		"delta logic rejects fingerprint-tamper" \
		"delta logic rejects geocode-row" \
		"delta logic rejects pre-has-location" \
		"backfill wait observes completion before timeout" \
		"backfill wait fails closed when completion never appears" \
		"default DB user versions are distinct 10 and 11" \
		"help describes distinct pre/post DB user versions" \
		"single --db-user-version is rejected"; do
		printf '%s\n' "$output" | grep -Fq "SELF-TEST PASS: $expected" || missing="$missing
  $expected"
	done
	if [[ -z "$missing" ]]; then
		pass "--self-test covers the expected schema/backfill/prefs/fingerprint/geocode cases"
	else
		fail "--self-test is missing expected case(s):$missing"
	fi

	if [[ -z "$(find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'penny-upgrade-self-test.*' -print -quit 2>/dev/null)" ]]; then
		pass "--self-test leaves no temporary directory behind"
	else
		fail "--self-test leaked a temporary directory"
	fi
}

main() {
	[[ -x "$SCRIPT_UNDER_TEST" ]] || {
		printf '[cli-test] ERROR: not executable: %s\n' "$SCRIPT_UNDER_TEST" >&2
		exit 1
	}
	WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/penny-upgrade-cli-test.XXXXXX")"
	chmod 700 "$WORK_DIR"

	test_static
	test_help_and_options
	test_no_device_contact
	test_mocked_dry_run
	test_mocked_already_installed
	test_self_test_coverage

	printf '[cli-test] %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
	[[ $FAIL_COUNT -eq 0 ]]
}

main "$@"
