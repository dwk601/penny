#!/usr/bin/env bash
# Rehearse Penny's replacement upgrade, including the Room 10 -> 11
# transaction-location schema/backfill, on an Android emulator.
# This script intentionally has no uninstall or package-data-clear operation.

set -Eeuo pipefail
umask 077

readonly PACKAGE="com.dwk.flowmoney"
readonly ACTIVITY="com.dwk.flowmoney/.MainActivity"
readonly WIDGET_PROVIDER="com.dwk.flowmoney.PennyWidgetProvider"
readonly OLD_VERSION_NAME_DEFAULT="1.0.13"
readonly OLD_VERSION_CODE_DEFAULT="113"
readonly NEW_VERSION_NAME_DEFAULT="1.0.14"
readonly NEW_VERSION_CODE_DEFAULT="114"
readonly PRE_DB_USER_VERSION_DEFAULT="10"
readonly POST_DB_USER_VERSION_DEFAULT="11"
readonly PARSEABLE_TX_ID="upgrade-simplefin-parseable"
readonly UNPARSEABLE_TX_ID="upgrade-simplefin-unparseable"
readonly LOCAL_EXPENSE_TX_ID="upgrade-local-expense"
readonly LOCAL_INCOME_TX_ID="upgrade-local-income"
readonly PARSEABLE_PROVIDER_DESCRIPTION="REHEARSAL CAFE #1001 AUSTIN TX"
readonly UNPARSEABLE_PROVIDER_DESCRIPTION="REHEARSAL CAFE STORE SEATTLE WA"
readonly EXPECTED_BACKFILL_CITY="AUSTIN"
readonly EXPECTED_BACKFILL_STATE="TX"
readonly MERCHANT_RULE_KEY="rehearsal cafe"
readonly BACKFILL_COMPLETION_PREFS_RELATIVE="shared_prefs/transaction_location_backfill.xml"
readonly BACKFILL_COMPLETION_PREF_NAME="v11_location_backfill_complete"
readonly SEED_MARKER_EXPECTED="4|1|1|1|1|1|1|1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SERIAL=""
OLD_APK=""
NEW_APK=""
OUTPUT_DIR=""
OLD_VERSION_NAME="$OLD_VERSION_NAME_DEFAULT"
OLD_VERSION_CODE="$OLD_VERSION_CODE_DEFAULT"
NEW_VERSION_NAME="$NEW_VERSION_NAME_DEFAULT"
NEW_VERSION_CODE="$NEW_VERSION_CODE_DEFAULT"
PRE_DB_USER_VERSION="$PRE_DB_USER_VERSION_DEFAULT"
POST_DB_USER_VERSION="$POST_DB_USER_VERSION_DEFAULT"
WITH_SIMPLEFIN_CREDENTIAL=0
DRY_RUN=0
SELF_TEST=0
COMPLETED=0
DEVICE_MUTATED=0
UI_DUMP_TIMEOUT_SECONDS=15
UI_DUMP_MAX_ATTEMPTS=3
UI_DUMP_RETRY_DELAY_SECONDS=1
BACKFILL_POLL_TIMEOUT_SECONDS=60
BACKFILL_POLL_INTERVAL_SECONDS=1

ADB_BIN=""
APKANALYZER_BIN=""
APKSIGNER_BIN=""
SQLITE3_BIN=""
PYTHON3_BIN=""
SHA256_BIN=""
SHA256_STYLE=""
EMULATOR_AVD_NAME=""
EMULATOR_HARDWARE=""
EMULATOR_BOOT_HARDWARE=""
EMULATOR_KERNEL_QEMU=""
EMULATOR_BOOT_QEMU=""
EMULATOR_CHARACTERISTICS=""

usage() {
	cat <<'USAGE'
Usage:
  scripts/verify-upgrade.sh \
    --serial emulator-5554 \
    --old-apk /secure/archive/Penny-v1.0.13-debug.apk \
    --new-apk /secure/candidate/Penny-v1.0.14-debug.apk \
    [--output-dir captures/upgrade-rehearsal-YYYYmmddTHHMMSSZ] \
    [--with-simplefin-credential]

Required safety properties:
  * --serial is mandatory and must be an online, user-0 Android Emulator/AVD.
    A physical device, an ambiguous implicit device, or a non-QEMU serial is rejected.
  * com.dwk.flowmoney must not already be installed. Use a fresh/restored AVD;
    this script will never uninstall it or clear its data.
  * The candidate is installed only with: adb -s SERIAL install -r NEW_APK.
    There is no downgrade, uninstall, clear-data, or replacement fallback.
  * The APKs must be package com.dwk.flowmoney, versions 1.0.13/113 and
    1.0.14/114, and have the same single signing-certificate SHA-256 digest.
  * Archived and candidate snapshots use distinct Room/SQLite user_version
    values (defaults 10 and 11). A single --db-user-version is rejected.

Options:
  --old-version-code N       Expected archived versionCode (default: 113)
  --new-version-code N       Expected candidate versionCode (default: 114)
  --pre-db-user-version N    Expected archived Room/SQLite user_version (default: 10)
  --post-db-user-version N   Expected candidate Room/SQLite user_version (default: 11)
  --with-simplefin-credential
                             Pause after the archived app starts so the operator
                             can connect SimpleFIN inside the app. No token or
                             access URL is accepted by or printed from this script.
  --dry-run                  Read-only tool/device/APK preflight. With no other
                             arguments, print the resolved tools and plan only.
  --self-test                Run isolated mock regression tests without touching
                             an adb device, APK, or rehearsal output.
  -h, --help                 Show this help without touching a device.

Environment overrides:
  ADB, APKANALYZER, APKSIGNER, SQLITE3, PYTHON3

Interactive steps:
  The operator must confirm an AVD snapshot, optionally connect SimpleFIN in the
  app, add exactly one Penny widget through the launcher, switch Penny to a
  non-Overview tab, and visually attest the widget body (Overview) and plus-button
  (Add transaction) routes. Generic ADB has no portable launcher widget-allocation
  API, so the script verifies the non-Overview -> Overview state transition plus
  page-specific selected-tab/editor semantics instead of treating always-visible
  navigation or FAB labels as route proof.

Artifacts:
  The output directory is mode 0700 and contains mode-0600 run-as snapshots,
  including the DB/WAL/SHM when present, both preference files, files/,
  no_backup/, and simplefin_access_url.bin when present. Canonical comparison
  preserves credentials/widget/files/settings and pre-existing logical rows,
  and permits only the expected 10->11 location schema/backfill/completion
  deltas. It can contain private financial data and encrypted credential
  material; store and delete it safely.
USAGE
}

log() {
	printf '[upgrade-rehearsal] %s\n' "$*"
}

warn() {
	printf '[upgrade-rehearsal] WARNING: %s\n' "$*" >&2
}

die() {
	printf '[upgrade-rehearsal] ERROR: %s\n' "$*" >&2
	exit 1
}

on_exit() {
	local status=$?
	if [[ $status -ne 0 && $DEVICE_MUTATED -eq 1 && $COMPLETED -eq 0 ]]; then
		warn "Rehearsal stopped after modifying the AVD. Do not uninstall or clear data to recover."
		warn "Keep the captured artifacts and restore the operator-created AVD snapshot before retrying."
	fi
}
trap on_exit EXIT

require_value() {
	local option="$1"
	local value="${2:-}"
	[[ -n "$value" ]] || die "$option requires a value"
}

validate_db_user_versions() {
	[[ "$PRE_DB_USER_VERSION" =~ ^[0-9]+$ ]] || die "--pre-db-user-version must be an integer"
	[[ "$POST_DB_USER_VERSION" =~ ^[0-9]+$ ]] || die "--post-db-user-version must be an integer"
	((POST_DB_USER_VERSION > PRE_DB_USER_VERSION)) ||
		die "candidate DB user_version must be greater than archived DB user_version"
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--serial)
		require_value "$1" "${2:-}"
		SERIAL="$2"
		shift 2
		;;
	--old-apk)
		require_value "$1" "${2:-}"
		OLD_APK="$2"
		shift 2
		;;
	--new-apk)
		require_value "$1" "${2:-}"
		NEW_APK="$2"
		shift 2
		;;
	--output-dir)
		require_value "$1" "${2:-}"
		OUTPUT_DIR="$2"
		shift 2
		;;
	--old-version-code)
		require_value "$1" "${2:-}"
		OLD_VERSION_CODE="$2"
		shift 2
		;;
	--new-version-code)
		require_value "$1" "${2:-}"
		NEW_VERSION_CODE="$2"
		shift 2
		;;
	--pre-db-user-version)
		require_value "$1" "${2:-}"
		PRE_DB_USER_VERSION="$2"
		shift 2
		;;
	--post-db-user-version)
		require_value "$1" "${2:-}"
		POST_DB_USER_VERSION="$2"
		shift 2
		;;
	--db-user-version)
		die "--db-user-version is not accepted because a single user_version cannot describe the 10->11 migration; use --pre-db-user-version and --post-db-user-version"
		;;
	--with-simplefin-credential)
		WITH_SIMPLEFIN_CREDENTIAL=1
		shift
		;;
	--dry-run)
		DRY_RUN=1
		shift
		;;
	--self-test)
		SELF_TEST=1
		shift
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		die "Unknown option: $1 (use --help)"
		;;
	esac
done

[[ "$OLD_VERSION_CODE" =~ ^[0-9]+$ ]] || die "--old-version-code must be an integer"
[[ "$NEW_VERSION_CODE" =~ ^[0-9]+$ ]] || die "--new-version-code must be an integer"
((NEW_VERSION_CODE > OLD_VERSION_CODE)) || die "candidate versionCode must be greater than archived versionCode"
validate_db_user_versions

sdk_roots() {
	local local_sdk=""
	[[ -n "${ANDROID_SDK_ROOT:-}" ]] && printf '%s\n' "$ANDROID_SDK_ROOT"
	[[ -n "${ANDROID_HOME:-}" ]] && printf '%s\n' "$ANDROID_HOME"
	if [[ -f "$REPO_ROOT/local.properties" ]]; then
		local_sdk="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tail -n 1)"
		local_sdk="${local_sdk//\\:/:}"
		local_sdk="${local_sdk//\\\\/\\}"
		[[ -n "$local_sdk" ]] && printf '%s\n' "$local_sdk"
	fi
	printf '%s\n' "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"
}

command_or_path() {
	local requested="$1"
	if [[ "$requested" == */* ]]; then
		if [[ -x "$requested" ]]; then
			printf '%s\n' "$requested"
		fi
	else
		command -v "$requested" 2>/dev/null || true
	fi
	# Missing/non-executable candidates are represented by successful empty
	# output so assignment under set -e reaches resolve_tools' curated error.
	return 0
}

version_key() {
	# Android build-tool directory names are numeric dotted versions. A fixed
	# width key avoids relying on GNU sort -V, which is absent on older macOS.
	awk -F. '{printf "%09d%09d%09d\n", $1 + 0, $2 + 0, $3 + 0}' <<<"${1%%-*}"
}

latest_build_tool() {
	local tool="$1"
	local sdk candidate best="" best_key="" candidate_key
	while IFS= read -r sdk; do
		[[ -d "$sdk/build-tools" ]] || continue
		while IFS= read -r candidate; do
			[[ -x "$candidate" ]] || continue
			candidate_key="$(version_key "$(basename "$(dirname "$candidate")")")"
			if [[ -z "$best" || "$candidate_key" > "$best_key" ]]; then
				best="$candidate"
				best_key="$candidate_key"
			fi
		done < <(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" -print 2>/dev/null)
	done < <(sdk_roots | awk 'NF && !seen[$0]++')
	if [[ -n "$best" ]]; then
		printf '%s\n' "$best"
	fi
	return 0
}

find_apkanalyzer() {
	local sdk candidate resolved
	resolved="$(command_or_path "${APKANALYZER:-apkanalyzer}")"
	[[ -n "$resolved" ]] && {
		printf '%s\n' "$resolved"
		return
	}
	while IFS= read -r sdk; do
		for candidate in \
			"$sdk/cmdline-tools/latest/bin/apkanalyzer" \
			"$sdk/tools/bin/apkanalyzer"; do
			[[ -x "$candidate" ]] && {
				printf '%s\n' "$candidate"
				return
			}
		done
		if [[ -d "$sdk/cmdline-tools" ]]; then
			candidate="$(find "$sdk/cmdline-tools" -mindepth 3 -maxdepth 3 -type f -name apkanalyzer -print 2>/dev/null | sort | tail -n 1)"
			[[ -n "$candidate" && -x "$candidate" ]] && {
				printf '%s\n' "$candidate"
				return
			}
		fi
	done < <(sdk_roots | awk 'NF && !seen[$0]++')
	return 0
}

find_adb() {
	local sdk candidate resolved
	resolved="$(command_or_path "${ADB:-adb}")"
	[[ -n "$resolved" ]] && {
		printf '%s\n' "$resolved"
		return
	}
	while IFS= read -r sdk; do
		candidate="$sdk/platform-tools/adb"
		[[ -x "$candidate" ]] && {
			printf '%s\n' "$candidate"
			return
		}
	done < <(sdk_roots | awk 'NF && !seen[$0]++')
	return 0
}

resolve_tools() {
	ADB_BIN="$(find_adb)"
	APKANALYZER_BIN="$(find_apkanalyzer)"
	APKSIGNER_BIN="$(command_or_path "${APKSIGNER:-apksigner}")"
	[[ -n "$APKSIGNER_BIN" ]] || APKSIGNER_BIN="$(latest_build_tool apksigner)"
	SQLITE3_BIN="$(command_or_path "${SQLITE3:-sqlite3}")"
	PYTHON3_BIN="$(command_or_path "${PYTHON3:-python3}")"

	if command -v sha256sum >/dev/null 2>&1; then
		SHA256_BIN="$(command -v sha256sum)"
		SHA256_STYLE="sha256sum"
	elif command -v shasum >/dev/null 2>&1; then
		SHA256_BIN="$(command -v shasum)"
		SHA256_STYLE="shasum"
	fi

	[[ -x "$ADB_BIN" ]] || die "adb not found; set ADB or install Android platform-tools"
	[[ -x "$APKANALYZER_BIN" ]] || die "apkanalyzer not found; set APKANALYZER or install Android command-line tools"
	[[ -x "$APKSIGNER_BIN" ]] || die "apksigner not found; set APKSIGNER or install Android build-tools"
	[[ -x "$SQLITE3_BIN" ]] || die "sqlite3 not found; set SQLITE3 or install the host sqlite3 CLI"
	[[ -x "$PYTHON3_BIN" ]] || die "python3 not found; set PYTHON3 or install Python 3 for safe tar canonicalization"
	[[ -x "$SHA256_BIN" ]] || die "sha256sum or shasum is required"
}

sha256_file() {
	local file="$1"
	if [[ "$SHA256_STYLE" == "sha256sum" ]]; then
		"$SHA256_BIN" "$file" | awk '{print toupper($1)}'
	else
		"$SHA256_BIN" -a 256 "$file" | awk '{print toupper($1)}'
	fi
}

sha256_stream() {
	if [[ "$SHA256_STYLE" == "sha256sum" ]]; then
		"$SHA256_BIN" | awk '{print toupper($1)}'
	else
		"$SHA256_BIN" -a 256 | awk '{print toupper($1)}'
	fi
}

strip_quotes_and_cr() {
	tr -d '\r' | sed -e 's/^"//' -e 's/"$//' | tail -n 1
}

apk_manifest_value() {
	local apk="$1"
	local field="$2"
	"$APKANALYZER_BIN" manifest "$field" "$apk" | strip_quotes_and_cr
}

apk_cert_digest() {
	local apk="$1"
	local cert_output digest_lines digest_count digest
	if ! cert_output="$("$APKSIGNER_BIN" verify --print-certs "$apk")"; then
		die "APK signature verification failed: $apk"
	fi
	digest_lines="$(printf '%s\n' "$cert_output" |
		awk -F': ' '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print toupper($2)}' |
		tr -d '\r')"
	digest_count="$(printf '%s\n' "$digest_lines" | awk 'NF {count++} END {print count+0}')"
	[[ "$digest_count" == "1" ]] || die "Exactly one APK signer is required: $apk"
	digest="$(printf '%s\n' "$digest_lines" | awk 'NF {print; exit}')"
	[[ "$digest" =~ ^[0-9A-F]{64}$ ]] || die "Malformed signing certificate digest for $apk"
	printf '%s\n' "$digest"
}

apk_metadata() {
	local apk="$1"
	local application_id version_name version_code cert_digest
	application_id="$(apk_manifest_value "$apk" application-id)" || return 1
	version_name="$(apk_manifest_value "$apk" version-name)" || return 1
	version_code="$(apk_manifest_value "$apk" version-code)" || return 1
	cert_digest="$(apk_cert_digest "$apk")" || return 1
	[[ -n "$application_id" && -n "$version_name" && "$version_code" =~ ^[0-9]+$ ]] || return 1
	[[ "$cert_digest" =~ ^[0-9A-F]{64}$ ]] || return 1
	printf '%s\t%s\t%s\t%s\n' "$application_id" "$version_name" "$version_code" "$cert_digest"
}

OLD_METADATA=""
NEW_METADATA=""
OLD_CERT=""
NEW_CERT=""

preflight_apks() {
	[[ -f "$OLD_APK" && -r "$OLD_APK" ]] || die "Archived APK is not a readable file: $OLD_APK"
	[[ -f "$NEW_APK" && -r "$NEW_APK" ]] || die "Candidate APK is not a readable file: $NEW_APK"

	OLD_METADATA="$(apk_metadata "$OLD_APK")" || die "Could not read complete archived APK metadata"
	NEW_METADATA="$(apk_metadata "$NEW_APK")" || die "Could not read complete candidate APK metadata"

	local old_package old_name old_code new_package new_name new_code
	IFS=$'\t' read -r old_package old_name old_code OLD_CERT <<<"$OLD_METADATA"
	IFS=$'\t' read -r new_package new_name new_code NEW_CERT <<<"$NEW_METADATA"

	[[ "$OLD_CERT" =~ ^[0-9A-F]{64}$ ]] || die "Archived APK certificate SHA-256 is missing or malformed"
	[[ "$NEW_CERT" =~ ^[0-9A-F]{64}$ ]] || die "Candidate APK certificate SHA-256 is missing or malformed"
	[[ "$old_package" == "$PACKAGE" ]] || die "Archived APK package is $old_package, expected $PACKAGE"
	[[ "$new_package" == "$PACKAGE" ]] || die "Candidate APK package is $new_package, expected $PACKAGE"
	[[ "$old_name" == "$OLD_VERSION_NAME" ]] || die "Archived versionName is $old_name, expected $OLD_VERSION_NAME"
	[[ "$old_code" == "$OLD_VERSION_CODE" ]] || die "Archived versionCode is $old_code, expected $OLD_VERSION_CODE"
	[[ "$new_name" == "$NEW_VERSION_NAME" ]] || die "Candidate versionName is $new_name, expected $NEW_VERSION_NAME"
	[[ "$new_code" == "$NEW_VERSION_CODE" ]] || die "Candidate versionCode is $new_code, expected $NEW_VERSION_CODE"
	[[ "$OLD_CERT" == "$NEW_CERT" ]] || die "Archived and candidate signing certificate SHA-256 digests differ"

	log "APK package/version transition verified: $PACKAGE $old_name/$old_code -> $new_name/$new_code"
	log "Signing certificate SHA-256: $OLD_CERT"
}

adb_cmd() {
	"$ADB_BIN" -s "$SERIAL" "$@"
}

shell_cmd() {
	adb_cmd shell "$@"
}

run_as_sh() {
	local command="$1"
	# Force a non-PTY shell-v2 stream. This preserves stdin bytes and waits for
	# the remote sh/run-as exit status through adb's status-bearing shell channel.
	# Keep the complete nested sh -c invocation in one argument so adb's device
	# shell does not lose redirections or compound-command status.
	adb_cmd shell -T "run-as '$PACKAGE' sh -c \"$command\""
}

trim_device_output() {
	tr -d '\r' | tail -n 1
}

preflight_emulator() {
	[[ -n "$SERIAL" ]] || die "--serial is required; implicit adb device selection is forbidden"
	[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] ||
		die "Serial $SERIAL is not an Android Emulator serial (expected emulator-NUMBER)"

	local state kernel_qemu boot_qemu characteristics hardware boot_hardware
	local current_user avd_identity avd_name console_status identity_line_count
	state="$(adb_cmd get-state 2>/dev/null | trim_device_output || true)"
	[[ "$state" == "device" ]] || die "Emulator $SERIAL is not online (state: ${state:-missing})"

	if ! kernel_qemu="$(shell_cmd getprop ro.kernel.qemu | trim_device_output)"; then
		die "Could not read ro.kernel.qemu from $SERIAL"
	fi
	[[ -z "$kernel_qemu" || "$kernel_qemu" == "1" ]] ||
		die "ro.kernel.qemu is present but not 1; refusing possible physical device"

	if ! boot_qemu="$(shell_cmd getprop ro.boot.qemu | trim_device_output)"; then
		die "Could not read ro.boot.qemu from $SERIAL"
	fi
	[[ "$boot_qemu" == "1" ]] || die "ro.boot.qemu is not 1; refusing possible physical device"

	if ! characteristics="$(shell_cmd getprop ro.build.characteristics | trim_device_output)"; then
		die "Could not read ro.build.characteristics from $SERIAL"
	fi
	case ",${characteristics//[[:space:]]/}," in
	*,emulator,*) ;;
	*) die "ro.build.characteristics lacks an exact emulator characteristic" ;;
	esac

	if ! hardware="$(shell_cmd getprop ro.hardware | trim_device_output)"; then
		die "Could not read ro.hardware from $SERIAL"
	fi
	case "$hardware" in
	ranchu | goldfish) ;;
	*) die "ro.hardware is not a recognized ranchu/goldfish emulator family: ${hardware:-missing}" ;;
	esac

	if ! boot_hardware="$(shell_cmd getprop ro.boot.hardware | trim_device_output)"; then
		die "Could not read ro.boot.hardware from $SERIAL"
	fi
	case "$boot_hardware" in
	"" | ranchu | goldfish) ;;
	*) die "ro.boot.hardware is not a recognized ranchu/goldfish emulator family: $boot_hardware" ;;
	esac

	if ! avd_identity="$(adb_cmd emu avd name 2>&1)"; then
		die "Emulator console AVD identity query failed for $SERIAL"
	fi
	avd_identity="${avd_identity//$'\r'/}"
	identity_line_count="$(printf '%s\n' "$avd_identity" | awk 'END {print NR}')"
	avd_name="$(printf '%s\n' "$avd_identity" | sed -n '1p')"
	console_status="$(printf '%s\n' "$avd_identity" | sed -n '2p')"
	[[ "$identity_line_count" == "2" && "$console_status" == "OK" &&
		"$avd_name" =~ ^[A-Za-z0-9._-]+$ && "$avd_name" != "OK" && "$avd_name" != "KO" ]] ||
		die "Emulator console returned a malformed AVD identity for $SERIAL"

	if ! current_user="$(shell_cmd am get-current-user | trim_device_output)"; then
		die "Could not determine the current Android user on $SERIAL"
	fi
	[[ "$current_user" == "0" ]] || die "Current Android user is $current_user; this rehearsal requires user 0"

	EMULATOR_AVD_NAME="$avd_name"
	EMULATOR_HARDWARE="$hardware"
	EMULATOR_BOOT_HARDWARE="$boot_hardware"
	EMULATOR_KERNEL_QEMU="$kernel_qemu"
	EMULATOR_BOOT_QEMU="$boot_qemu"
	EMULATOR_CHARACTERISTICS="$characteristics"
	log "Positively identified emulator $SERIAL (AVD $avd_name, $hardware, QEMU user 0, ro.kernel.qemu=${kernel_qemu:-absent})"
}

package_installed() {
	shell_cmd pm path "$PACKAGE" >/dev/null 2>&1
}

print_tools_and_plan() {
	cat <<EOF
Resolved tools:
  adb:         $ADB_BIN
  apkanalyzer: $APKANALYZER_BIN
  apksigner:   $APKSIGNER_BIN
  sqlite3:     $SQLITE3_BIN
  python3:     $PYTHON3_BIN

Read/write plan (actual mode only):
  1. Require a fresh/restored QEMU AVD and install archived $OLD_VERSION_NAME/$OLD_VERSION_CODE.
  2. Snapshot, seed deterministic v$PRE_DB_USER_VERSION rows (local, parseable/unparseable
     SimpleFIN, profile/account/tombstone/merchant rule) and both preference contracts, and verify quick_check.
  3. Require one launcher-bound Penny widget and capture the baseline canonical fingerprint,
     using a sorted file type/content manifest rather than tar metadata for files/.
  4. Run exactly: adb -s SERIAL install -r NEW_APK
  5. Poll for the asynchronous location backfill completion preference, then fail closed on
     package, certificate, version, UID, widget, quick_check, preserved-data, or unexpected-delta mismatch.
  6. Permit only the expected $PRE_DB_USER_VERSION->$POST_DB_USER_VERSION location schema/backfill/completion deltas.
  7. Prove a selected non-Overview tab before HOME, then require the widget body to
     return to selected Overview; verify the Add editor and retain secure snapshots.
EOF
}

confirm_exact() {
	local expected="$1"
	local prompt="$2"
	local answer=""
	printf '\n%s\nType %s to continue: ' "$prompt" "$expected" >/dev/tty
	IFS= read -r answer </dev/tty
	[[ "$answer" == "$expected" ]] || die "Confirmation did not match $expected"
}

prepare_rehearsal() {
	resolve_tools

	if [[ $DRY_RUN -eq 1 && -z "$SERIAL" && -z "$OLD_APK" && -z "$NEW_APK" ]]; then
		print_tools_and_plan
		log "Dry run complete; no device command was issued and no files were created."
		COMPLETED=1
		exit 0
	fi

	[[ -n "$SERIAL" ]] || die "--serial is required"
	[[ -n "$OLD_APK" ]] || die "--old-apk is required"
	[[ -n "$NEW_APK" ]] || die "--new-apk is required"
	preflight_emulator
	preflight_apks

	if package_installed; then
		die "$PACKAGE is already installed on $SERIAL. Restore a fresh AVD snapshot; do not uninstall or clear data."
	fi

	print_tools_and_plan

	if [[ $DRY_RUN -eq 1 ]]; then
		log "Read-only dry run passed. No install, launch, write, screenshot, or snapshot was performed."
		COMPLETED=1
		exit 0
	fi

	[[ -t 0 && -t 1 ]] || die "Actual rehearsal requires an interactive terminal for safety and launcher checks"
	confirm_exact "AVD-SNAPSHOT-READY" \
		"Confirm this is a disposable fresh AVD, $PACKAGE is absent, and an AVD snapshot exists."

	if [[ -z "$OUTPUT_DIR" ]]; then
		OUTPUT_DIR="$REPO_ROOT/captures/upgrade-rehearsal-$(date -u +%Y%m%dT%H%M%SZ)"
	fi
	[[ ! -e "$OUTPUT_DIR" ]] || die "Output path already exists: $OUTPUT_DIR"
	mkdir -p "$OUTPUT_DIR"
	chmod 700 "$OUTPUT_DIR"

	cat >"$OUTPUT_DIR/apk-preflight.txt" <<EOF
package=$PACKAGE
archived_apk=$OLD_APK
archived_sha256=$(sha256_file "$OLD_APK")
archived_version_name=$OLD_VERSION_NAME
archived_version_code=$OLD_VERSION_CODE
candidate_apk=$NEW_APK
candidate_sha256=$(sha256_file "$NEW_APK")
candidate_version_name=$NEW_VERSION_NAME
candidate_version_code=$NEW_VERSION_CODE
signing_certificate_sha256=$OLD_CERT
serial=$SERIAL
avd_name=$EMULATOR_AVD_NAME
ro_kernel_qemu=${EMULATOR_KERNEL_QEMU:-absent}
ro_boot_qemu=$EMULATOR_BOOT_QEMU
ro_build_characteristics=$EMULATOR_CHARACTERISTICS
ro_hardware=$EMULATOR_HARDWARE
ro_boot_hardware=${EMULATOR_BOOT_HARDWARE:-absent}
EOF
	chmod 600 "$OUTPUT_DIR/apk-preflight.txt"
}

install_archived() {
	local output
	log "Installing archived $OLD_VERSION_NAME on emulator (fresh install; no replacement flag)"
	if ! output="$(adb_cmd install "$OLD_APK" 2>&1)"; then
		printf '%s\n' "$output" >&2
		die "Archived APK install failed. Restore the AVD snapshot; do not uninstall or clear data."
	fi
	[[ "$output" == *Success* ]] || die "Archived APK install did not report Success"
	DEVICE_MUTATED=1
}

install_candidate_replacement_only() {
	local output
	log "Installing candidate strictly with adb install -r (no fallback is permitted)"
	if ! output="$("$ADB_BIN" -s "$SERIAL" install -r "$NEW_APK" 2>&1)"; then
		printf '%s\n' "$output" >&2
		die "REPLACEMENT FAILED. STOP: do not launch, uninstall, clear data, retry with -d, or use another install mode."
	fi
	[[ "$output" == *Success* ]] || die "Replacement did not report Success; stop without fallback"
}

launch_app() {
	local output
	if ! output="$(shell_cmd am start -W -n "$ACTIVITY" 2>&1)"; then
		printf '%s\n' "$output" >&2
		die "Could not launch $ACTIVITY"
	fi
	[[ "$output" == *"Status: ok"* || "$output" == *"Warning: Activity not started"* ]] ||
		die "Activity launch did not report Status: ok"
	sleep 3
}

force_stop_app() {
	shell_cmd am force-stop "$PACKAGE" >/dev/null
	sleep 1
}

installed_base_path() {
	local path
	path="$(shell_cmd pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | awk '/base\.apk$/ {print; exit}')"
	[[ -n "$path" ]] || die "Could not locate installed base.apk for $PACKAGE"
	printf '%s\n' "$path"
}

package_uid() {
	local uid
	uid="$(shell_cmd dumpsys package "$PACKAGE" |
		tr -d '\r' |
		sed -n 's/^[[:space:]]*userId=//p' |
		head -n 1)"
	[[ "$uid" =~ ^[0-9]+$ ]] || die "Could not determine installed package UID"
	printf '%s\n' "$uid"
}

verify_installed_apk() {
	local expected_apk="$1"
	local expected_name="$2"
	local expected_code="$3"
	local phase_dir="$4"
	local expected_cert="$5"
	local base_path pulled metadata actual_package actual_name actual_code actual_cert

	mkdir -p "$phase_dir"
	base_path="$(installed_base_path)"
	pulled="$phase_dir/installed-base.apk"
	adb_cmd exec-out cat "$base_path" >"$pulled"
	chmod 600 "$pulled"
	[[ -s "$pulled" ]] || die "Installed base.apk pull was empty"
	[[ "$(sha256_file "$pulled")" == "$(sha256_file "$expected_apk")" ]] ||
		die "Installed base.apk bytes differ from the selected APK"

	metadata="$(apk_metadata "$pulled")" || die "Could not read complete installed APK metadata"
	IFS=$'\t' read -r actual_package actual_name actual_code actual_cert <<<"$metadata"
	[[ "$actual_package" == "$PACKAGE" ]] || die "Installed package mismatch: $actual_package"
	[[ "$actual_name" == "$expected_name" ]] || die "Installed versionName mismatch: $actual_name"
	[[ "$actual_code" == "$expected_code" ]] || die "Installed versionCode mismatch: $actual_code"
	[[ "$actual_cert" == "$expected_cert" ]] || die "Installed signing certificate mismatch"

	cat >"$phase_dir/installed-metadata.txt" <<EOF
package=$actual_package
version_name=$actual_name
version_code=$actual_code
certificate_sha256=$actual_cert
base_apk_sha256=$(sha256_file "$pulled")
uid=$(package_uid)
EOF
	chmod 600 "$phase_dir/installed-metadata.txt"
	log "Installed APK verified: $actual_name/$actual_code, package and certificate match"
}

remote_exists() {
	local relative="$1"
	run_as_sh "test -f '$relative'" >/dev/null 2>&1
}

remote_dir_exists() {
	local relative="$1"
	run_as_sh "test -d '$relative'" >/dev/null 2>&1
}

capture_remote_file() {
	local relative="$1"
	local destination="$2"
	local required="$3"
	local manifest="$4"
	mkdir -p "$(dirname "$destination")"
	if remote_exists "$relative"; then
		adb_cmd exec-out run-as "$PACKAGE" cat "$relative" >"$destination.part"
		mv "$destination.part" "$destination"
		chmod 600 "$destination"
		printf '%s\tPRESENT\t%s\t%s\n' \
			"$relative" "$(wc -c <"$destination" | tr -d ' ')" "$(sha256_file "$destination")" >>"$manifest"
	else
		printf '%s\tMISSING\t-\t-\n' "$relative" >>"$manifest"
		[[ "$required" == "required" ]] || return 0
		die "Required run-as file is missing: $relative"
	fi
}

capture_remote_dir() {
	local relative="$1"
	local destination="$2"
	local required="$3"
	local manifest="$4"
	if ! remote_dir_exists "$relative"; then
		printf '%s/\tMISSING\t-\t-\n' "$relative" >>"$manifest"
		[[ "$required" == "required" ]] || return 0
		die "Required run-as directory is missing: $relative/"
	fi
	adb_cmd exec-out run-as "$PACKAGE" tar -cf - "$relative" >"$destination.part"
	mv "$destination.part" "$destination"
	chmod 600 "$destination"
	printf '%s/\tARCHIVE\t%s\t%s\n' \
		"$relative" "$(wc -c <"$destination" | tr -d ' ')" "$(sha256_file "$destination")" >>"$manifest"
}

capture_snapshot() {
	local name="$1"
	local prefs_required="$2"
	local dir="$OUTPUT_DIR/$name"
	local raw="$dir/raw"
	local manifest="$dir/run-as-manifest.tsv"
	mkdir -p "$raw/databases" "$raw/shared_prefs" "$raw/no_backup"
	: >"$manifest"
	chmod 600 "$manifest"

	capture_remote_file "databases/flow_money.db" "$raw/databases/flow_money.db" required "$manifest"
	capture_remote_file "databases/flow_money.db-wal" "$raw/databases/flow_money.db-wal" optional "$manifest"
	capture_remote_file "databases/flow_money.db-shm" "$raw/databases/flow_money.db-shm" optional "$manifest"
	capture_remote_file "shared_prefs/flow_money.xml" "$raw/shared_prefs/flow_money.xml" "$prefs_required" "$manifest"
	capture_remote_file "shared_prefs/simplefin_migration_cleanup.xml" \
		"$raw/shared_prefs/simplefin_migration_cleanup.xml" "$prefs_required" "$manifest"
	capture_remote_file "$BACKFILL_COMPLETION_PREFS_RELATIVE" \
		"$raw/$BACKFILL_COMPLETION_PREFS_RELATIVE" optional "$manifest"
	capture_remote_dir "files" "$raw/files.tar" optional "$manifest"
	capture_remote_dir "no_backup" "$raw/no_backup.tar" optional "$manifest"
	capture_remote_file "no_backup/simplefin_access_url.bin" \
		"$raw/no_backup/simplefin_access_url.bin" optional "$manifest"
	log "Captured secure run-as snapshot: $name"
}

install_private_file() {
	local source="$1"
	local relative="$2"
	local parent temporary write_command source_digest temporary_digest destination_digest
	[[ -f "$source" && -r "$source" ]] || die "Private seed source is not readable: $source"
	[[ "$relative" =~ ^[A-Za-z0-9._/-]+$ && "$relative" != /* && "$relative" != *//* ]] ||
		die "Unsafe app-private destination path"
	[[ "/$relative/" != */../* && "/$relative/" != */./* ]] ||
		die "Unsafe app-private destination path"

	source_digest="$(sha256_file "$source")" || die "Could not hash private seed source"
	[[ "$source_digest" =~ ^[0-9A-F]{64}$ ]] || die "Private seed source hash is malformed"

	# Stream unmodified bytes over a non-PTY adb shell-v2 stdin channel. The
	# status-bearing call does not return until remote cat and chmod complete.
	# Keep both the existing destination and the app-private temporary out of
	# shared device storage.
	parent="${relative%/*}"
	[[ "$parent" != "$relative" ]] || parent="."
	temporary="${relative}.penny-upgrade-${$}.part"
	write_command="umask 077; mkdir -p '$parent' && rm -f '$temporary' && cat > '$temporary' && chmod 600 '$temporary'"
	if ! run_as_sh "$write_command" <"$source" >/dev/null; then
		run_as_sh "rm -f '$temporary'" >/dev/null 2>&1 || true
		die "Could not complete app-private temporary write: $relative"
	fi

	# Independently verify the completed temporary before the atomic rename.
	# Transport or temporary-digest failure must never remove/replace an existing
	# destination; only the disposable temporary is cleaned.
	if ! temporary_digest="$(adb_cmd exec-out run-as "$PACKAGE" cat "$temporary" | sha256_stream)"; then
		run_as_sh "rm -f '$temporary'" >/dev/null 2>&1 || true
		die "Could not hash app-private temporary after streaming: $relative"
	fi
	if [[ "$temporary_digest" != "$source_digest" ]]; then
		run_as_sh "rm -f '$temporary'" >/dev/null 2>&1 || true
		die "App-private temporary did not match the source: $relative"
	fi

	if ! run_as_sh "mv -f '$temporary' '$relative'" >/dev/null; then
		run_as_sh "rm -f '$temporary'" >/dev/null 2>&1 || true
		die "Could not atomically install app-private file: $relative"
	fi

	# Verify the destination as a separate post-rename gate. Never delete it on
	# failure: it may be the only remaining coherent copy and evidence must remain.
	if ! destination_digest="$(adb_cmd exec-out run-as "$PACKAGE" cat "$relative" | sha256_stream)"; then
		die "Could not verify installed app-private file: $relative"
	fi
	[[ "$destination_digest" == "$source_digest" ]] ||
		die "Installed app-private file did not match the source: $relative"
}

install_private_database() {
	local source="$1"
	# With the app stopped and the seed collapsed into its main file, remove stale
	# sidecars before (never after) atomically replacing the database. Otherwise
	# SQLite could associate an old WAL/SHM with the newly installed main file.
	run_as_sh "rm -f 'databases/flow_money.db-wal' 'databases/flow_money.db-shm'" >/dev/null ||
		die "Could not remove stale database sidecars before main database replacement"
	install_private_file "$source" "databases/flow_money.db"
}

sqlite_quick_check() {
	local snapshot_db_dir="$1"
	local work_dir="$2"
	local result
	rm -rf "$work_dir"
	mkdir -p "$work_dir"
	cp "$snapshot_db_dir/flow_money.db" "$work_dir/flow_money.db"
	[[ ! -f "$snapshot_db_dir/flow_money.db-wal" ]] || cp "$snapshot_db_dir/flow_money.db-wal" "$work_dir/flow_money.db-wal"
	[[ ! -f "$snapshot_db_dir/flow_money.db-shm" ]] || cp "$snapshot_db_dir/flow_money.db-shm" "$work_dir/flow_money.db-shm"
	result="$("$SQLITE3_BIN" -batch "$work_dir/flow_money.db" 'PRAGMA quick_check;' | tr -d '\r')"
	[[ "$result" == "ok" ]] || die "SQLite quick_check failed: $result"
	printf '%s\n' "$result"
}

write_seed_preferences() {
	local destination="$1"
	mkdir -p "$destination"
	cat >"$destination/flow_money.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="room_migrated" value="true" />
    <string name="transactions_csv">Date,Merchant,Category,Note,Amount,Recurring
2024-01-02,Legacy marker,Other,Upgrade rehearsal preference marker,-1.23,</string>
</map>
EOF
	cat >"$destination/simplefin_migration_cleanup.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="v5_disconnection_complete" value="true" />
</map>
EOF
	chmod 600 "$destination"/*.xml
}

seed_database_and_preferences() {
	local initial_db_dir="$OUTPUT_DIR/00-archived-initial/raw/databases"
	local seed_dir="$OUTPUT_DIR/seed"
	local seed_db="$seed_dir/flow_money.db"
	local now_ms expense_ms income_ms
	mkdir -p "$seed_dir/db-source"
	cp "$initial_db_dir/flow_money.db" "$seed_dir/db-source/flow_money.db"
	[[ ! -f "$initial_db_dir/flow_money.db-wal" ]] || cp "$initial_db_dir/flow_money.db-wal" "$seed_dir/db-source/flow_money.db-wal"
	[[ ! -f "$initial_db_dir/flow_money.db-shm" ]] || cp "$initial_db_dir/flow_money.db-shm" "$seed_dir/db-source/flow_money.db-shm"

	sqlite_quick_check "$seed_dir/db-source" "$seed_dir/db-working" >"$seed_dir/initial-quick-check.txt"
	seed_db="$seed_dir/db-working/flow_money.db"
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA user_version;' | tr -d '\r')" == "$PRE_DB_USER_VERSION" ]] ||
		die "Archived database user_version is not $PRE_DB_USER_VERSION"
	assert_pre_migration_schema "$seed_db" "archived seed"

	now_ms=$(($(date +%s) * 1000))
	expense_ms=$((now_ms - 86400000))
	income_ms=$((now_ms - 172800000))

	"$SQLITE3_BIN" -batch -bail "$seed_db" <<SQL >/dev/null
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
INSERT INTO transactions
    (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source,
     accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride,
     flowKind, flowKindOverride)
VALUES
    ('$LOCAL_EXPENSE_TX_ID', $expense_ms, 'Rehearsal Market', 'Groceries', 'v10 replacement marker',
     -1234, 'Monthly', 'local', NULL, NULL, NULL, NULL, NULL, 'NORMAL', NULL),
    ('$LOCAL_INCOME_TX_ID', $income_ms, 'Rehearsal Payroll', 'Salary', 'v10 replacement marker',
     250000, NULL, 'local', NULL, NULL, NULL, NULL, NULL, 'NORMAL', NULL),
    ('$PARSEABLE_TX_ID', $expense_ms, 'Rehearsal Cafe', 'Coffee', 'v10 replacement marker',
     -5678, NULL, 'simplefin', 'upgrade-account-001', 'Upgrade Checking', NULL,
     '$PARSEABLE_PROVIDER_DESCRIPTION', NULL, 'NORMAL', NULL),
    ('$UNPARSEABLE_TX_ID', $expense_ms, 'Rehearsal Cafe Store', 'Coffee', 'v10 replacement marker',
     -4321, NULL, 'simplefin', 'upgrade-account-001', 'Upgrade Checking', NULL,
     '$UNPARSEABLE_PROVIDER_DESCRIPTION', NULL, 'NORMAL', NULL);
INSERT OR IGNORE INTO simplefin_profile
    (id, connectionId, connectedAtEpochMillis, lastSyncAttemptAtEpochMillis,
     lastSuccessfulSyncAtEpochMillis, lastError, isPaused, automaticSyncsPerDay)
VALUES
    ('default', 'upgrade-rehearsal-connection', $now_ms, $now_ms, $now_ms,
     'Upgrade rehearsal: intentionally paused', 1, 4);
UPDATE simplefin_profile
SET lastError = 'Upgrade rehearsal: intentionally paused', isPaused = 1, automaticSyncsPerDay = 4
WHERE id = 'default';
INSERT INTO simplefin_accounts
    (accountId, name, currency, institutionName, balanceAmount,
     availableBalanceAmount, balanceDateEpochSeconds, lastSeenAtEpochMillis)
VALUES
    ('upgrade-account-001', 'Upgrade Checking', 'USD', 'Rehearsal Credit Union',
     '1234.56', '1200.00', $((now_ms / 1000)), $now_ms);
INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis)
VALUES ('upgrade-ignored-transaction', $now_ms);
INSERT INTO merchant_rules (normalizedProviderMerchant, category, merchantOverride)
VALUES ('$MERCHANT_RULE_KEY', 'Coffee', 'Rehearsal Cafe');
COMMIT;
PRAGMA wal_checkpoint(FULL);
SQL

	local marker_counts
	marker_counts="$(query_seed_marker_counts "$seed_db")"
	[[ "$marker_counts" == "$SEED_MARKER_EXPECTED" ]] ||
		die "Seed marker verification failed (expected $SEED_MARKER_EXPECTED, got $marker_counts)"
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA quick_check;' | tr -d '\r')" == "ok" ]] ||
		die "Seed database quick_check failed"

	# Collapse the stopped app's coherent DB/WAL state into one main database before
	# replacing the initial empty database. The initial sidecars are already captured.
	"$SQLITE3_BIN" -batch -bail "$seed_db" 'PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;' >/dev/null
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA quick_check;' | tr -d '\r')" == "ok" ]] ||
		die "Collapsed seed database quick_check failed"

	write_seed_preferences "$seed_dir/preferences"
	install_private_database "$seed_db"
	install_private_file "$seed_dir/preferences/flow_money.xml" "shared_prefs/flow_money.xml"
	install_private_file "$seed_dir/preferences/simplefin_migration_cleanup.xml" \
		"shared_prefs/simplefin_migration_cleanup.xml"
	log "Seeded local rows, parseable/unparseable SimpleFIN descriptors, merchant rule, and SimpleFIN profile/account/ignored rows"
}

capture_widget_record() {
	local destination_dir="$1"
	local dump="$destination_dir/appwidget-dumpsys.txt"
	local record="$destination_dir/widget-record.txt"
	local canonical="$destination_dir/widget-canonical.txt"
	mkdir -p "$destination_dir"
	shell_cmd dumpsys appwidget >"$dump"
	tr -d '\r' <"$dump" >"$dump.normalized"
	mv "$dump.normalized" "$dump"
	chmod 600 "$dump"

	awk -v package="$PACKAGE" -v provider="$WIDGET_PROVIDER" '
        function emit() {
            if (record != "" && index(record, package) && index(record, provider)) {
                printf "%s", record
                matches++
            }
            record=""
        }
        /^Widgets:/ {widgets=1; next}
        /^Hosts:/ {if (widgets) emit(); widgets=0}
        widgets && /^  \[[0-9]+\] id=/ {emit(); record=$0 ORS; next}
        widgets && record != "" {record=record $0 ORS}
        END {if (widgets) emit(); if (matches != 1) exit 42}
    ' "$dump" >"$record" || die "Expected exactly one bound Penny widget; add one widget and remove no app data"
	chmod 600 "$record"

	awk '
        /^  \[[0-9]+\] id=/ {
            line=$0; sub(/^.*id=/, "", line); sub(/[[:space:]].*$/, "", line); print "id=" line
        }
        /hostId=HostId/ {line=$0; sub(/^[[:space:]]*/, "", line); print line}
        /provider=ProviderId/ {line=$0; sub(/^[[:space:]]*/, "", line); print line}
    ' "$record" >"$canonical"
	[[ "$(wc -l <"$canonical" | tr -d ' ')" == "3" ]] ||
		die "Could not canonicalize Penny widget binding"
	chmod 600 "$canonical"
}

capture_screen() {
	local destination="$1"
	adb_cmd exec-out screencap -p >"$destination"
	chmod 600 "$destination"
	[[ -s "$destination" ]] || die "Screenshot capture failed: $destination"
}

verify_pref_contracts() {
	local raw="$1"
	grep -Eq '<boolean[[:space:]]+name="room_migrated"[[:space:]]+value="true"[[:space:]]*/>' \
		"$raw/shared_prefs/flow_money.xml" || die "flow_money.xml room_migrated is not true"
	grep -Fq '<string name="transactions_csv">' "$raw/shared_prefs/flow_money.xml" ||
		die "flow_money.xml transactions_csv key is missing"
	grep -Eq '<boolean[[:space:]]+name="v5_disconnection_complete"[[:space:]]+value="true"[[:space:]]*/>' \
		"$raw/shared_prefs/simplefin_migration_cleanup.xml" ||
		die "simplefin_migration_cleanup.xml completion key is not true"
}

completion_preference_is_true() {
	local file="$1"
	[[ -f "$file" ]] || return 1
	grep -Eq '<boolean[[:space:]]+name="'"$BACKFILL_COMPLETION_PREF_NAME"'"[[:space:]]+value="true"[[:space:]]*/>' "$file"
}

query_seed_marker_counts() {
	local db="$1"
	"$SQLITE3_BIN" -batch -noheader -separator '|' "$db" \
		"SELECT (SELECT count(*) FROM transactions WHERE id LIKE 'upgrade-%'), (SELECT count(*) FROM transactions WHERE id='$LOCAL_EXPENSE_TX_ID' AND recurringInterval='Monthly'), (SELECT count(*) FROM transactions WHERE id='$PARSEABLE_TX_ID' AND source='simplefin' AND providerDescription='$PARSEABLE_PROVIDER_DESCRIPTION'), (SELECT count(*) FROM transactions WHERE id='$UNPARSEABLE_TX_ID' AND source='simplefin' AND providerDescription='$UNPARSEABLE_PROVIDER_DESCRIPTION'), (SELECT count(*) FROM simplefin_profile WHERE id='default'), (SELECT count(*) FROM simplefin_accounts WHERE accountId='upgrade-account-001'), (SELECT count(*) FROM simplefin_ignored_transactions WHERE transactionId='upgrade-ignored-transaction'), (SELECT count(*) FROM merchant_rules WHERE normalizedProviderMerchant='$MERCHANT_RULE_KEY');" |
		tr -d '\r'
}

sqlite_user_version() {
	"$SQLITE3_BIN" -batch -noheader "$1" 'PRAGMA user_version;' | tr -d '\r'
}

schema_has_table() {
	local db="$1"
	local table="$2"
	local count
	count="$("$SQLITE3_BIN" -batch -noheader "$db" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table';" | tr -d '\r')"
	[[ "$count" == "1" ]]
}

schema_has_column() {
	local db="$1"
	local table="$2"
	local column="$3"
	local count
	count="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$db" "PRAGMA table_info($table);" | awk -F'|' -v column="$column" 'BEGIN {count=0} $2 == column {count++} END {print count+0}')"
	[[ "$count" == "1" ]]
}

dump_schema_contract() {
	local db="$1"
	local destination="$2"
	local part="${destination}.part"
	local table count
	{
		printf 'user_version:%s\n' "$(sqlite_user_version "$db")"
		while IFS= read -r table; do
			[[ -n "$table" ]] || continue
			[[ "$table" =~ ^[A-Za-z0-9_]+$ ]] || die "Refusing to dump unsafe table name: $table"
			printf 'table:%s\n' "$table"
			"$SQLITE3_BIN" -batch -noheader -separator '|' "$db" "PRAGMA table_info($table);" |
				awk -F'|' '{printf "col:%s|%s|%s|%s\n", $2, toupper($3), $4, $6}'
			"$SQLITE3_BIN" -batch -noheader -separator '|' "$db" "PRAGMA index_list($table);" |
				awk -F'|' 'NF {printf "index:%s|%s|%s\n", $2, $3, $4}'
		done < <("$SQLITE3_BIN" -batch -noheader "$db" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name;")
		count="$("$SQLITE3_BIN" -batch -noheader "$db" "SELECT count(*) FROM sqlite_master WHERE type IN ('view','trigger') AND name NOT LIKE 'sqlite_%';" | tr -d '\r')"
		printf 'extra_objects:%s\n' "$count"
	} >"$part"
	mv "$part" "$destination"
	chmod 600 "$destination"
}

schema_without_expected_post_delta() {
	awk '
		BEGIN { skip=0 }
		/^user_version:/ { next }
		/^table:place_geocodes$/ { skip=1; next }
		/^table:/ { skip=0 }
		skip { next }
		/^col:locationCity\|/ { next }
		/^col:locationState\|/ { next }
		/^col:locationCountry\|/ { next }
		{ print }
	' "$1"
}

assert_pre_migration_schema() {
	local db="$1"
	local label="$2"
	schema_has_column "$db" transactions locationCity &&
		die "$label already has transactions.locationCity"
	schema_has_column "$db" transactions locationState &&
		die "$label already has transactions.locationState"
	schema_has_column "$db" transactions locationCountry &&
		die "$label already has transactions.locationCountry"
	schema_has_table "$db" place_geocodes &&
		die "$label already has place_geocodes"
}

assert_post_migration_schema() {
	local db="$1"
	local label="$2"
	local spec expected count
	schema_has_column "$db" transactions locationCity ||
		die "$label is missing transactions.locationCity"
	schema_has_column "$db" transactions locationState ||
		die "$label is missing transactions.locationState"
	schema_has_column "$db" transactions locationCountry ||
		die "$label is missing transactions.locationCountry"
	schema_has_table "$db" place_geocodes ||
		die "$label is missing place_geocodes"
	spec="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$db" 'PRAGMA table_info(transactions);' |
		awk -F'|' '$2=="locationCity" || $2=="locationState" || $2=="locationCountry" {
			printf "%s|%s|%s|%s\n", $2, toupper($3), $4, $6
		}')"
	expected=$'locationCity|TEXT|0|0\nlocationState|TEXT|0|0\nlocationCountry|TEXT|0|0'
	[[ "$spec" == "$expected" ]] ||
		die "$label location columns are not nullable TEXT without a primary-key role: $spec"
	spec="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$db" 'PRAGMA table_info(place_geocodes);' |
		awk -F'|' '{printf "%s|%s|%s|%s\n", $2, toupper($3), $4, $6}')"
	expected=$'placeKey|TEXT|1|1\ncity|TEXT|0|0\nstate|TEXT|0|0\ncountry|TEXT|0|0\nlatitude|REAL|0|0\nlongitude|REAL|0|0\nresolvedAtEpochMillis|INTEGER|1|0'
	[[ "$spec" == "$expected" ]] ||
		die "$label place_geocodes shape is not entity-compatible: $spec"
	count="$("$SQLITE3_BIN" -batch -noheader "$db" 'SELECT count(*) FROM place_geocodes;' | tr -d '\r')"
	[[ "$count" == "0" ]] ||
		die "$label place_geocodes has $count unexpected row(s); the table must be empty after schema/backfill"
}

dump_preexisting_logical() {
	local db="$1"
	local destination="$2"
	"$SQLITE3_BIN" -batch -bail "$db" >"$destination" <<'SQL'
.mode quote
SELECT 'transactions', id, occurredAtEpochMillis, merchant, category, note, cents,
       recurringInterval, source, accountKey, accountName, reviewedAtEpochMillis,
       providerDescription, merchantOverride, flowKind, flowKindOverride
FROM transactions ORDER BY id;
SELECT 'simplefin_profile', id, connectionId, connectedAtEpochMillis,
       lastSyncAttemptAtEpochMillis, lastSuccessfulSyncAtEpochMillis, lastError,
       isPaused, automaticSyncsPerDay
FROM simplefin_profile ORDER BY id;
SELECT 'simplefin_accounts', accountId, name, currency, institutionName,
       balanceAmount, availableBalanceAmount, balanceDateEpochSeconds, lastSeenAtEpochMillis
FROM simplefin_accounts ORDER BY accountId;
SELECT 'simplefin_ignored_transactions', transactionId, ignoredAtEpochMillis, occurredAtEpochMillis
FROM simplefin_ignored_transactions ORDER BY transactionId;
SELECT 'simplefin_identity_state', id, reconciliationComplete
FROM simplefin_identity_state ORDER BY id;
SELECT 'merchant_rules', normalizedProviderMerchant, category, merchantOverride
FROM merchant_rules ORDER BY normalizedProviderMerchant;
SQL
	chmod 600 "$destination"
}

dump_location_values() {
	local db="$1"
	local destination="$2"
	"$SQLITE3_BIN" -batch -bail "$db" >"$destination" <<'SQL'
.mode quote
SELECT id, locationCity, locationState, locationCountry
FROM transactions ORDER BY id;
SQL
	chmod 600 "$destination"
}

assert_expected_backfill() {
	local db="$1"
	local label="$2"
	local parseable other_located
	parseable="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$db" \
		"SELECT IFNULL(locationCity, '<NULL>'), IFNULL(locationState, '<NULL>'), IFNULL(locationCountry, '<NULL>') FROM transactions WHERE id='$PARSEABLE_TX_ID';" |
		tr -d '\r')"
	[[ "$parseable" == "$EXPECTED_BACKFILL_CITY|$EXPECTED_BACKFILL_STATE|<NULL>" ]] ||
		die "$label parseable SimpleFIN backfill was $parseable, expected $EXPECTED_BACKFILL_CITY|$EXPECTED_BACKFILL_STATE|<NULL>"
	other_located="$("$SQLITE3_BIN" -batch -noheader "$db" \
		"SELECT count(*) FROM transactions WHERE id != '$PARSEABLE_TX_ID' AND (locationCity IS NOT NULL OR locationState IS NOT NULL OR locationCountry IS NOT NULL);" |
		tr -d '\r')"
	[[ "$other_located" == "0" ]] ||
		die "$label has $other_located unexpected located row(s); unparseable SimpleFIN and local rows must remain null"
}

assert_completion_preference() {
	local raw="$1"
	local expected="$2"
	local file="$raw/$BACKFILL_COMPLETION_PREFS_RELATIVE"
	if [[ "$expected" == "present" ]]; then
		completion_preference_is_true "$file" ||
			die "Location backfill completion preference is missing or not true"
	elif completion_preference_is_true "$file"; then
		die "Location backfill completion preference is present before the candidate upgrade"
	fi
}

fetch_remote_backfill_preference() {
	local destination="$1"
	rm -f "$destination"
	if remote_exists "$BACKFILL_COMPLETION_PREFS_RELATIVE"; then
		adb_cmd exec-out run-as "$PACKAGE" cat "$BACKFILL_COMPLETION_PREFS_RELATIVE" >"$destination.part"
		mv "$destination.part" "$destination"
		chmod 600 "$destination"
		return 0
	fi
	return 1
}

wait_for_location_backfill_completion() {
	local poll_dir="${1:-$OUTPUT_DIR/20-candidate/backfill-poll}"
	local elapsed=0
	local attempt=1
	local probe
	mkdir -p "$poll_dir"
	chmod 700 "$poll_dir"
	while ((elapsed < BACKFILL_POLL_TIMEOUT_SECONDS)); do
		probe="$poll_dir/attempt-${attempt}.xml"
		if fetch_remote_backfill_preference "$probe" && completion_preference_is_true "$probe"; then
			log "Location backfill completion preference observed after ${elapsed}s"
			return 0
		fi
		if [[ ! -f "$probe" ]]; then
			printf 'MISSING\n' >"$probe"
			chmod 600 "$probe"
		fi
		sleep "$BACKFILL_POLL_INTERVAL_SECONDS"
		elapsed=$((elapsed + BACKFILL_POLL_INTERVAL_SECONDS))
		attempt=$((attempt + 1))
	done
	die "Timed out after ${BACKFILL_POLL_TIMEOUT_SECONDS}s waiting for location backfill completion preference"
}

assert_expected_schema_delta() {
	local pre_schema="$1"
	local post_schema="$2"
	local pre_stripped post_stripped
	grep -Fq 'table:place_geocodes' "$pre_schema" &&
		die "Archived schema already contains place_geocodes"
	grep -Eq '^col:location(City|State|Country)\|' "$pre_schema" &&
		die "Archived schema already contains transaction location columns"
	grep -Fq 'table:place_geocodes' "$post_schema" ||
		die "Candidate schema is missing place_geocodes"
	grep -Eq '^col:locationCity\|' "$post_schema" ||
		die "Candidate schema is missing transactions.locationCity"
	grep -Eq '^col:locationState\|' "$post_schema" ||
		die "Candidate schema is missing transactions.locationState"
	grep -Eq '^col:locationCountry\|' "$post_schema" ||
		die "Candidate schema is missing transactions.locationCountry"
	pre_stripped="$(schema_without_expected_post_delta "$pre_schema")"
	post_stripped="$(schema_without_expected_post_delta "$post_schema")"
	if [[ "$pre_stripped" != "$post_stripped" ]]; then
		warn "Schema changed beyond the expected 10->11 location delta:"
		diff -u <(printf '%s\n' "$pre_stripped") <(printf '%s\n' "$post_stripped") >&2 || true
		die "Candidate schema has unexpected changes besides location columns and place_geocodes"
	fi
}

assert_upgrade_delta() {
	local archived="$1"
	local candidate="$2"
	local archived_db="$archived/canonical/db-working/flow_money.db"
	local candidate_db="$candidate/canonical/db-working/flow_money.db"

	[[ "$(sqlite_user_version "$archived_db")" == "$PRE_DB_USER_VERSION" ]] ||
		die "Archived snapshot user_version is not $PRE_DB_USER_VERSION"
	[[ "$(sqlite_user_version "$candidate_db")" == "$POST_DB_USER_VERSION" ]] ||
		die "Candidate snapshot user_version is not $POST_DB_USER_VERSION"

	assert_pre_migration_schema "$archived_db" "archived snapshot"
	assert_post_migration_schema "$candidate_db" "candidate snapshot"
	assert_expected_schema_delta "$archived/canonical/database-schema.txt" \
		"$candidate/canonical/database-schema.txt"

	if ! cmp -s "$archived/canonical/database-logical.txt" "$candidate/canonical/database-logical.txt"; then
		warn "Pre-existing logical values changed across replacement:"
		diff -u "$archived/canonical/database-logical.txt" "$candidate/canonical/database-logical.txt" >&2 || true
		die "Pre-existing logical database values are not unchanged"
	fi

	assert_expected_backfill "$candidate_db" "candidate snapshot"
	assert_completion_preference "$archived/raw" absent
	assert_completion_preference "$candidate/raw" present

	if ! cmp -s "$archived/canonical/fingerprint-components.txt" \
		"$candidate/canonical/fingerprint-components.txt"; then
		warn "Preserved canonical fingerprint components differ (hashes only):"
		diff -u "$archived/canonical/fingerprint-components.txt" \
			"$candidate/canonical/fingerprint-components.txt" >&2 || true
		die "Data/settings/files/credential/widget canonical fingerprint mismatch"
	fi
}

canonicalize_tar_directory() {
	local archive="$1"
	local root="$2"
	local destination="$3"
	local part="${destination}.part"

	# Read but never extract the untrusted archive. JSON safely represents every
	# relative path (including whitespace/newlines), and byte-wise path sorting
	# plus content hashes ignores tar order, mtime, uid/gid, mode, and headers.
	if ! "$PYTHON3_BIN" - "$archive" "$root" >"$part" <<'PY'
import hashlib
import json
import sys
import tarfile

archive_path, root = sys.argv[1:]
if not root or "/" in root or root in {".", ".."}:
    raise SystemExit("invalid archive root")

records = []
seen = set()
with tarfile.open(archive_path, mode="r:*") as archive:
    for member in archive:
        name = member.name.rstrip("/") if member.isdir() else member.name
        if name == root:
            relative = "."
        elif name.startswith(root + "/"):
            relative = name[len(root) + 1 :]
        else:
            raise SystemExit(f"archive member escapes {root}/: {name!r}")

        if relative != ".":
            parts = relative.split("/")
            if not relative or any(part in {"", ".", ".."} for part in parts):
                raise SystemExit(f"unsafe archive member path: {name!r}")
        if relative in seen:
            raise SystemExit(f"duplicate archive member path: {relative!r}")
        seen.add(relative)

        record = {"path": relative}
        if member.isdir():
            record.update(type="directory", sha256=None)
        elif member.isfile() or member.islnk():
            stream = archive.extractfile(member)
            if stream is None:
                raise SystemExit(f"could not read archive member: {name!r}")
            digest = hashlib.sha256()
            with stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            record.update(
                type="hardlink" if member.islnk() else "file",
                sha256=digest.hexdigest().upper(),
            )
            if member.islnk():
                record["link_target_sha256"] = hashlib.sha256(
                    member.linkname.encode("utf-8", "surrogateescape")
                ).hexdigest().upper()
        elif member.issym():
            record.update(
                type="symlink",
                sha256=hashlib.sha256(
                    member.linkname.encode("utf-8", "surrogateescape")
                ).hexdigest().upper(),
            )
        else:
            raise SystemExit(f"unsupported archive member type: {name!r}")
        records.append(record)

if "." not in seen:
    raise SystemExit(f"archive does not contain root directory {root!r}")
records.sort(key=lambda record: record["path"].encode("utf-8", "surrogateescape"))
for record in records:
    print(json.dumps(record, ensure_ascii=True, sort_keys=True, separators=(",", ":")))
PY
	then
		rm -f "$part"
		die "Could not create safe canonical manifest for $archive"
	fi
	mv "$part" "$destination"
	chmod 600 "$destination"
}

create_canonical_fingerprint() {
	local snapshot_name="$1"
	local widget_canonical="$2"
	local expected_user_version="$3"
	local dir="$OUTPUT_DIR/$snapshot_name"
	local raw="$dir/raw"
	local canonical="$dir/canonical"
	local db="$canonical/db-working/flow_money.db"
	local credential_component="MISSING"
	local files_component="MISSING"
	local marker_counts
	mkdir -p "$canonical"

	sqlite_quick_check "$raw/databases" "$canonical/db-working" >"$canonical/sqlite-quick-check.txt"
	[[ "$(sqlite_user_version "$db")" == "$expected_user_version" ]] ||
		die "$snapshot_name database user_version is not $expected_user_version"

	dump_preexisting_logical "$db" "$canonical/database-logical.txt"
	dump_schema_contract "$db" "$canonical/database-schema.txt"
	if schema_has_column "$db" transactions locationCity; then
		dump_location_values "$db" "$canonical/database-location.txt"
	else
		printf 'ABSENT\n' >"$canonical/database-location.txt"
		chmod 600 "$canonical/database-location.txt"
	fi

	marker_counts="$(query_seed_marker_counts "$db")"
	[[ "$marker_counts" == "$SEED_MARKER_EXPECTED" ]] ||
		die "$snapshot_name seed rows or Monthly recurrence are missing/duplicated: $marker_counts"

	verify_pref_contracts "$raw"
	if [[ -f "$raw/no_backup/simplefin_access_url.bin" ]]; then
		credential_component="PRESENT:$(sha256_file "$raw/no_backup/simplefin_access_url.bin")"
	fi
	if [[ $WITH_SIMPLEFIN_CREDENTIAL -eq 1 && "$credential_component" == "MISSING" ]]; then
		die "$snapshot_name is missing the required encrypted SimpleFIN credential file"
	fi
	if [[ -f "$raw/files.tar" ]]; then
		canonicalize_tar_directory "$raw/files.tar" files "$canonical/files-manifest.jsonl"
		files_component="PRESENT:$(sha256_file "$canonical/files-manifest.jsonl")"
	fi

	cat >"$canonical/fingerprint-components.txt" <<EOF
database_logical_sha256=$(sha256_file "$canonical/database-logical.txt")
flow_money_preferences_sha256=$(sha256_file "$raw/shared_prefs/flow_money.xml")
simplefin_cleanup_preferences_sha256=$(sha256_file "$raw/shared_prefs/simplefin_migration_cleanup.xml")
files_manifest=$files_component
simplefin_credential=$credential_component
widget_binding_sha256=$(sha256_file "$widget_canonical")
EOF
	chmod 600 "$canonical/fingerprint-components.txt"
	sha256_file "$canonical/fingerprint-components.txt" >"$canonical/fingerprint.sha256"
	chmod 600 "$canonical/fingerprint.sha256"
	log "$snapshot_name SQLite quick_check=ok; canonical fingerprint $(cat "$canonical/fingerprint.sha256")"
}

assert_foreground_activity() {
	local resumed
	resumed="$(shell_cmd dumpsys activity activities | tr -d '\r' | grep -m 1 'mResumedActivity' || true)"
	[[ "$resumed" == *"$PACKAGE"* && "$resumed" == *"MainActivity"* ]] ||
		die "Penny MainActivity is not the resumed activity"
}

sanitize_ui_dump() {
	local source="$1"
	local destination="$2"
	"$PYTHON3_BIN" - "$source" "$destination" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

source, destination = sys.argv[1:]
with open(source, "rb") as stream:
    captured = stream.read()

start = captured.find(b"<?xml")
end_marker = b"</hierarchy>"
end = captured.find(end_marker, start if start >= 0 else 0)
if (
    start < 0
    or end < 0
    or captured.count(b"<?xml") != 1
    or captured.count(b"<hierarchy") != 1
    or captured.count(end_marker) != 1
):
    raise SystemExit("uiautomator stdout did not contain exactly one complete hierarchy")
# A PTY-capable fallback is not currently needed, but normalize CR from adb
# transports and discard any status/prompt noise surrounding the XML.
payload = captured[start : end + len(end_marker)]
payload = payload.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
root = ET.fromstring(payload)
if root.tag != "hierarchy":
    raise SystemExit("uiautomator XML root is not hierarchy")

fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "wb", closefd=False) as stream:
        stream.write(payload)
finally:
    os.close(fd)
PY
}

run_bounded_capture() {
	local timeout_seconds="$1"
	local stdout_path="$2"
	local stderr_path="$3"
	shift 3
	"$PYTHON3_BIN" - "$timeout_seconds" "$stdout_path" "$stderr_path" "$@" <<'PY'
import os
import subprocess
import sys

timeout = int(sys.argv[1])
stdout_path, stderr_path = sys.argv[2:4]
command = sys.argv[4:]

def secure_open(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    return os.fdopen(fd, "wb")

with secure_open(stdout_path) as stdout, secure_open(stderr_path) as stderr:
    try:
        completed = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        stderr.write(f"host timeout after {timeout} seconds\n".encode("ascii"))
        raise SystemExit(124)
raise SystemExit(completed.returncode if completed.returncode >= 0 else 128 - completed.returncode)
PY
}

capture_ui_dump() {
	local destination="$1"
	local parent sanitized attempt transcript transport runner validation metadata status
	parent="$(dirname "$destination")"
	sanitized="${destination}.sanitized.part"
	mkdir -p "$parent"
	chmod 700 "$parent"
	rm -f "$destination" "$sanitized"

	attempt=1
	while ((attempt <= UI_DUMP_MAX_ATTEMPTS)); do
		transcript="${destination}.uiautomator-attempt-${attempt}.stdout.log"
		transport="${destination}.uiautomator-attempt-${attempt}.stderr.log"
		runner="${destination}.uiautomator-attempt-${attempt}.host-runner.stderr.log"
		validation="${destination}.uiautomator-attempt-${attempt}.validation.log"
		metadata="${destination}.uiautomator-attempt-${attempt}.status"
		: >"$transcript"
		: >"$transport"
		: >"$runner"
		: >"$validation"
		: >"$metadata"
		chmod 600 "$transcript" "$transport" "$runner" "$validation" "$metadata"

		# API 35 smoke testing proved that uiautomator can write directly to its
		# inherited stdout descriptor. This needs neither a controlling TTY nor a
		# device-side file, so sensitive hierarchy bytes go only to 0600 host logs.
		if run_bounded_capture "$UI_DUMP_TIMEOUT_SECONDS" "$transcript" "$transport" \
			"$ADB_BIN" -s "$SERIAL" exec-out uiautomator dump /proc/self/fd/1 2>"$runner"; then
			status=0
		else
			status=$?
		fi
		cat >"$metadata" <<EOF
attempt=$attempt
transport=adb-exec-out
remote_destination=/proc/self/fd/1
exit_status=$status
timeout_seconds=$UI_DUMP_TIMEOUT_SECONDS
EOF
		chmod 600 "$transcript" "$transport" "$runner" "$validation" "$metadata"

		if [[ $status -eq 0 ]]; then
			if sanitize_ui_dump "$transcript" "$sanitized" 2>"$validation"; then
				mv "$sanitized" "$destination"
				chmod 600 "$destination"
				[[ -s "$destination" ]] && return 0
			fi
		else
			printf 'validation skipped because adb transport exited %s\n' "$status" >"$validation"
			chmod 600 "$validation"
		fi

		rm -f "$sanitized"
		if ((attempt < UI_DUMP_MAX_ATTEMPTS)); then
			sleep "$UI_DUMP_RETRY_DELAY_SECONDS"
		fi
		attempt=$((attempt + 1))
	done
	rm -f "$sanitized"
	die "Could not stream and validate UI hierarchy; retained secure per-attempt diagnostics beside $destination"
}

ui_dump_selected_label_count() {
	local dump="$1"
	local label="$2"
	awk -v label="$label" '
        BEGIN { RS=">" }
        /<node[[:space:]]/ {
            labeled = index($0, "text=\"" label "\"") ||
                      index($0, "content-desc=\"" label "\"")
            if (labeled && index($0, "selected=\"true\"")) matches++
        }
        END { print matches + 0 }
    ' "$dump"
}

ui_dump_has_exactly_one_selected_label() {
	[[ "$(ui_dump_selected_label_count "$1" "$2")" == "1" ]]
}

ui_dump_has_selected_label() {
	[[ "$(ui_dump_selected_label_count "$1" "$2")" -gt 0 ]]
}

non_overview_precondition_dump_is_specific() {
	local dump="$1"
	if ui_dump_has_selected_label "$dump" "Overview"; then
		return 1
	fi
	if ui_dump_has_exactly_one_selected_label "$dump" "Transactions"; then
		if ui_dump_has_selected_label "$dump" "Insights"; then
			return 1
		fi
		return 0
	fi
	if ui_dump_has_exactly_one_selected_label "$dump" "Insights"; then
		if ui_dump_has_selected_label "$dump" "Transactions"; then
			return 1
		fi
		return 0
	fi
	return 1
}

overview_route_dump_is_specific() {
	local dump="$1"
	# Overview can also appear as an unselected top title, and seeded content can
	# contain duplicate Transactions labels. Anchor only to selected nav semantics.
	ui_dump_has_exactly_one_selected_label "$dump" "Overview" &&
		! ui_dump_has_selected_label "$dump" "Transactions" &&
		! ui_dump_has_selected_label "$dump" "Insights"
}

add_route_dump_is_specific() {
	local dump="$1"
	# The persistent FAB and Insights rows can expose content-desc values "Add
	# transaction" and "Edit transaction". Require one UI node subtree with exact
	# text attributes for the fresh title, close action, and expense-only controls.
	# An edit sheet cannot borrow an unrelated background Add label because its
	# candidate subtree also contains the exact Edit transaction title.
	"$PYTHON3_BIN" - "$dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

required = {"Add transaction", "Close", "Amount paid", "Save expense"}
rejected = {"Edit transaction", "Income received", "Save income"}
root = ET.parse(sys.argv[1]).getroot()
for candidate in root.iter("node"):
    texts = {
        node.attrib["text"]
        for node in candidate.iter("node")
        if node.attrib.get("text", "")
    }
    if required <= texts and not rejected.intersection(texts):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

verify_widget_routes() {
	local route_dir="$OUTPUT_DIR/20-candidate/routes"
	mkdir -p "$route_dir"

	# Establish a fail-closed state transition: a no-op body click cannot pass
	# after the operator and hierarchy have proved another tab is selected.
	launch_app
	confirm_exact "NON-OVERVIEW-READY" \
		"In Penny, switch to Transactions or Insights and leave exactly that tab selected. Do not press HOME; the script will verify this non-Overview precondition."
	assert_foreground_activity
	capture_ui_dump "$route_dir/non-overview-precondition-window.xml"
	non_overview_precondition_dump_is_specific "$route_dir/non-overview-precondition-window.xml" ||
		die "Widget body precondition lacked exactly one selected Transactions/Insights node or still selected Overview"
	capture_screen "$route_dir/non-overview-precondition.png"

	shell_cmd input keyevent HOME >/dev/null
	sleep 1
	confirm_exact "OVERVIEW-OK" \
		"Tap the Penny widget body (not +). Verify Penny returns from the selected non-Overview tab to Overview."
	assert_foreground_activity
	capture_ui_dump "$route_dir/overview-window.xml"
	overview_route_dump_is_specific "$route_dir/overview-window.xml" ||
		die "Widget body did not return the verified non-Overview precondition to exactly one selected Overview nav node"
	capture_screen "$route_dir/overview-route.png"

	shell_cmd input keyevent HOME >/dev/null
	sleep 1
	confirm_exact "ADD-OK" \
		"Tap the Penny widget + button. Verify a fresh expense Add transaction editor opens. Do not scroll the editor before typing ADD-OK; the hierarchy check must see Amount paid."
	assert_foreground_activity
	capture_ui_dump "$route_dir/add-window.xml"
	add_route_dump_is_specific "$route_dir/add-window.xml" ||
		die "Add route lacked exact Add transaction title/Close/Amount paid/Save expense editor text"
	capture_screen "$route_dir/add-route.png"
	log "Both widget routes were operator-attested and state-transition/page-specific UI-verified"
}

SELF_TEST_FAILURE_COUNT=0

self_test_pass() {
	log "SELF-TEST PASS: $1"
}

self_test_fail() {
	warn "SELF-TEST FAIL: $1"
	SELF_TEST_FAILURE_COUNT=$((SELF_TEST_FAILURE_COUNT + 1))
	return 0
}

make_mock_executable() {
	local destination="$1"
	cat >"$destination" <<'SH'
#!/bin/sh
exit 0
SH
	chmod 700 "$destination"
}

missing_tool_message() {
	case "$1" in
	ADB) printf '%s\n' "adb not found; set ADB or install Android platform-tools" ;;
	APKANALYZER) printf '%s\n' "apkanalyzer not found; set APKANALYZER or install Android command-line tools" ;;
	APKSIGNER) printf '%s\n' "apksigner not found; set APKSIGNER or install Android build-tools" ;;
	SQLITE3) printf '%s\n' "sqlite3 not found; set SQLITE3 or install the host sqlite3 CLI" ;;
	PYTHON3) printf '%s\n' "python3 not found; set PYTHON3 or install Python 3 for safe tar canonicalization" ;;
	*) return 1 ;;
	esac
}

self_test_missing_tool_case() {
	local test_root="$1"
	local target="$2"
	local mode="$3"
	local output="$test_root/tool-${target}-${mode}.log"
	local expected status
	expected="$(missing_tool_message "$target")"

	if (
		# Intentionally isolate command lookup to exercise missing fallbacks.
		# shellcheck disable=SC2123
		PATH="$test_root/path"
		local empty_sdk="$test_root/empty-sdk"
		sdk_roots() { printf '%s\n' "$empty_sdk"; }
		ADB="$test_root/tools/adb"
		APKANALYZER="$test_root/tools/apkanalyzer"
		APKSIGNER="$test_root/tools/apksigner"
		SQLITE3="$test_root/tools/sqlite3"
		PYTHON3="$test_root/tools/python3"
		case "$mode" in
		override) printf -v "$target" '%s' "$test_root/missing/$target" ;;
		non-executable) printf -v "$target" '%s' "$test_root/non-executable/$target" ;;
		fallback) unset "$target" ;;
		esac
		resolve_tools
	) >"$output" 2>&1; then
		status=0
	else
		status=$?
	fi
	chmod 600 "$output"

	if [[ $status -ne 0 ]] && grep -Fq "ERROR: $expected" "$output"; then
		self_test_pass "$target $mode candidate reaches curated diagnostic"
	else
		self_test_fail "$target $mode candidate did not produce: $expected"
	fi
}

self_test_preflight_case() {
	local test_root="$1"
	local name="$2"
	local expectation="$3"
	local expected_message="$4"
	local mock_serial="$5"
	local mock_state="$6"
	local mock_kernel="$7"
	local mock_boot_qemu="$8"
	local mock_characteristics="$9"
	shift 9
	local mock_hardware="$1"
	local mock_boot_hardware="$2"
	local mock_avd_output="$3"
	local mock_avd_status="$4"
	local mock_user="$5"
	local output="$test_root/preflight-${name}.log"
	local status

	if (
		SERIAL="$mock_serial"
		adb_cmd() {
			case "$*" in
			get-state) printf '%s\n' "$mock_state" ;;
			"shell getprop ro.kernel.qemu") printf '%s\n' "$mock_kernel" ;;
			"shell getprop ro.boot.qemu") printf '%s\n' "$mock_boot_qemu" ;;
			"shell getprop ro.build.characteristics") printf '%s\n' "$mock_characteristics" ;;
			"shell getprop ro.hardware") printf '%s\n' "$mock_hardware" ;;
			"shell getprop ro.boot.hardware") printf '%s\n' "$mock_boot_hardware" ;;
			"emu avd name") printf '%s' "$mock_avd_output"; return "$mock_avd_status" ;;
			"shell am get-current-user") printf '%s\n' "$mock_user" ;;
			*) return 97 ;;
			esac
		}
		preflight_emulator
	) >"$output" 2>&1; then
		status=0
	else
		status=$?
	fi
	chmod 600 "$output"

	if [[ "$expectation" == "pass" ]]; then
		if [[ $status -eq 0 ]] && grep -Fq "Positively identified emulator" "$output"; then
			self_test_pass "emulator preflight accepts $name"
		else
			self_test_fail "emulator preflight rejected $name"
		fi
	elif [[ $status -ne 0 ]] && grep -Fq "ERROR: $expected_message" "$output"; then
		self_test_pass "emulator preflight rejects $name"
	else
		self_test_fail "emulator preflight did not reject $name with: $expected_message"
	fi
}

portable_mode() {
	local path="$1"
	stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path"
}

run_self_test_ui_capture_configured() {
	local mock_adb="$1"
	local counter="$2"
	local args_log="$3"
	local destination="$4"
	local behavior="$5"
	local python3_bin="$6"
	# These locals dynamically shadow the production globals for this call only.
	local ADB_BIN="$mock_adb"
	local PYTHON3_BIN="$python3_bin"
	local SERIAL="emulator-5554"
	local UI_DUMP_TIMEOUT_SECONDS=1
	local UI_DUMP_MAX_ATTEMPTS=3
	local UI_DUMP_RETRY_DELAY_SECONDS=0

	export MOCK_ADB_COUNTER="$counter"
	export MOCK_ADB_ARGS="$args_log"
	export MOCK_ADB_BEHAVIOR="$behavior"
	capture_ui_dump "$destination"
}

run_self_test_ui_capture_case() (
	# Keep capture_ui_dump's production die/exit behavior inside this process.
	run_self_test_ui_capture_configured "$@"
)

run_self_test_ui_sanitize_case() {
	local python3_bin="$1"
	shift
	local PYTHON3_BIN="$python3_bin"
	sanitize_ui_dump "$@"
}

self_test_ui_capture() {
	local test_root="$1"
	local mock_adb="$test_root/mock-adb"
	local retry_counter="$test_root/mock-adb-retry.counter"
	local retry_args_log="$test_root/mock-adb-retry.args"
	local capture_dir="$test_root/ui-capture"
	local destination="$capture_dir/window.xml"
	local exhausted_counter="$test_root/mock-adb-exhausted.counter"
	local exhausted_args_log="$test_root/mock-adb-exhausted.args"
	local exhausted_capture_dir="$test_root/ui-capture-exhausted"
	local exhausted_destination="$exhausted_capture_dir/window.xml"
	local exhausted_output="$test_root/ui-capture-exhausted.output.log"
	local retry_output="$test_root/ui-capture-retry.output.log"
	local duplicate_source="$test_root/duplicate-hierarchy.stdout"
	local duplicate_destination="$test_root/duplicate-hierarchy.xml"
	local duplicate_diagnostic="$test_root/duplicate-hierarchy.validation.log"
	local parent_adb_bin="$ADB_BIN"
	local parent_python3_bin="$PYTHON3_BIN"
	local parent_serial="$SERIAL"
	local parent_timeout="$UI_DUMP_TIMEOUT_SECONDS"
	local parent_max_attempts="$UI_DUMP_MAX_ATTEMPTS"
	local parent_retry_delay="$UI_DUMP_RETRY_DELAY_SECONDS"
	local python3_bin retry_status exhausted_status attempt file directory
	local exhausted_evidence=1
	local bad_mode=0

	python3_bin="$(command -v python3)"
	printf '0\n' >"$retry_counter"
	printf '0\n' >"$exhausted_counter"
	: >"$retry_args_log"
	: >"$exhausted_args_log"
	chmod 600 "$retry_counter" "$exhausted_counter" "$retry_args_log" "$exhausted_args_log"
	cat >"$mock_adb" <<'SH'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$MOCK_ADB_ARGS"
count="$(cat "$MOCK_ADB_COUNTER")"
count=$((count + 1))
printf '%s\n' "$count" >"$MOCK_ADB_COUNTER"
case "$MOCK_ADB_BEHAVIOR" in
retry-then-success)
	case "$count" in
	1)
		exec sleep 2
		;;
	2)
		printf 'mock transport warning\n' >&2
		printf 'status without XML\r\n'
		;;
	*)
		printf 'shell status noise\r\n<?xml version="1.0" encoding="UTF-8"?>\r\n<hierarchy rotation="0"><node text="Amount paid" /></hierarchy>\r\nUI hierarchy status\r\nmock-prompt$ '
		;;
	esac
	;;
always-malformed)
	printf 'mock malformed attempt %s\n' "$count" >&2
	printf 'status without XML\r\n'
	;;
*)
	exit 98
	;;
esac
SH
	chmod 700 "$mock_adb"

	if run_self_test_ui_capture_case "$mock_adb" "$exhausted_counter" "$exhausted_args_log" \
		"$exhausted_destination" always-malformed "$python3_bin" >"$exhausted_output" 2>&1; then
		exhausted_status=0
	else
		exhausted_status=$?
	fi
	chmod 600 "$exhausted_output"
	for attempt in 1 2 3; do
		if [[ ! -f "$exhausted_destination.uiautomator-attempt-${attempt}.status" ]] ||
			! grep -Fq "attempt=$attempt" "$exhausted_destination.uiautomator-attempt-${attempt}.status"; then
			exhausted_evidence=0
		fi
	done
	if [[ $exhausted_status -ne 0 ]] &&
		[[ "$(cat "$exhausted_counter")" == "3" ]] &&
		[[ "$(wc -l <"$exhausted_args_log" | tr -d ' ')" == "3" ]] &&
		[[ ! -e "$exhausted_destination" ]] &&
		[[ $exhausted_evidence -eq 1 ]] &&
		grep -Fq 'ERROR: Could not stream and validate UI hierarchy' "$exhausted_output"; then
		self_test_pass "UI dump fails closed after exactly the maximum malformed attempts"
	else
		self_test_fail "UI dump exhaustion did not fail closed after the maximum attempts"
	fi

	if run_self_test_ui_capture_case "$mock_adb" "$retry_counter" "$retry_args_log" \
		"$destination" retry-then-success "$python3_bin" >"$retry_output" 2>&1; then
		retry_status=0
	else
		retry_status=$?
	fi
	chmod 600 "$retry_output"
	if [[ $retry_status -eq 0 ]] &&
		grep -Fq '<hierarchy rotation="0">' "$destination" &&
		! grep -Fq 'shell status noise' "$destination" &&
		! grep -Fq $'\r' "$destination"; then
		self_test_pass "UI dump self-tests continue after isolated exhaustion and sanitize a later retry"
	else
		self_test_fail "UI dump retry mock did not produce one sanitized hierarchy"
	fi

	printf '%s\n%s\n' \
		'<?xml version="1.0"?><hierarchy></hierarchy>' \
		'<?xml version="1.0"?><hierarchy></hierarchy>' >"$duplicate_source"
	chmod 600 "$duplicate_source"
	if run_self_test_ui_sanitize_case "$python3_bin" "$duplicate_source" \
		"$duplicate_destination" 2>"$duplicate_diagnostic"; then
		self_test_fail "UI dump sanitizer accepted duplicate hierarchies"
	elif grep -Fq 'exactly one complete hierarchy' "$duplicate_diagnostic" &&
		[[ ! -e "$duplicate_destination" ]]; then
		chmod 600 "$duplicate_diagnostic"
		self_test_pass "UI dump sanitizer rejects duplicate hierarchies"
	else
		self_test_fail "UI dump sanitizer rejected duplicates without its curated diagnostic"
	fi

	if grep -Fq 'exec-out uiautomator dump /proc/self/fd/1' "$retry_args_log" &&
		[[ "$(wc -l <"$retry_args_log" | tr -d ' ')" == "3" ]] &&
		! grep -Eq '/sdcard|/data/local/tmp|/dev/tty' "$retry_args_log" "$exhausted_args_log"; then
		self_test_pass "UI dump uses only inherited stdout and never a staged/shared/TTY path"
	else
		self_test_fail "UI dump invoked an unexpected adb transport or destination"
	fi

	if grep -Fq 'exit_status=124' "$destination.uiautomator-attempt-1.status" &&
		grep -Fq 'host timeout after 1 seconds' "$destination.uiautomator-attempt-1.stderr.log" &&
		grep -Fq 'exactly one complete hierarchy' "$destination.uiautomator-attempt-2.validation.log" &&
		grep -Fq 'mock transport warning' "$destination.uiautomator-attempt-2.stderr.log"; then
		self_test_pass "UI dump preserves timeout, transport, status, and validation diagnostics"
	else
		self_test_fail "UI dump diagnostics were missing"
	fi

	for directory in "$capture_dir" "$exhausted_capture_dir"; do
		[[ "$(portable_mode "$directory")" == "700" ]] || bad_mode=1
		for file in "$directory"/*; do
			[[ ! -f "$file" || "$(portable_mode "$file")" == "600" ]] || bad_mode=1
		done
	done
	if [[ $bad_mode -eq 0 ]]; then
		self_test_pass "UI dump host directories/files are 0700/0600 after success and exhaustion"
	else
		self_test_fail "UI dump host permissions were not 0700/0600"
	fi

	if [[ "$ADB_BIN" == "$parent_adb_bin" ]] &&
		[[ "$PYTHON3_BIN" == "$parent_python3_bin" ]] &&
		[[ "$SERIAL" == "$parent_serial" ]] &&
		[[ "$UI_DUMP_TIMEOUT_SECONDS" == "$parent_timeout" ]] &&
		[[ "$UI_DUMP_MAX_ATTEMPTS" == "$parent_max_attempts" ]] &&
		[[ "$UI_DUMP_RETRY_DELAY_SECONDS" == "$parent_retry_delay" ]]; then
		self_test_pass "UI capture cases do not leak ADB, serial, Python, or retry globals"
	else
		self_test_fail "UI capture cases leaked production globals into the parent self-test"
	fi
}

self_test_run_script() {
	"$SCRIPT_DIR/verify-upgrade.sh" "$@"
}

self_test_expect_error() {
	local name="$1"
	local expected="$2"
	shift 2
	local output status
	if output="$(self_test_run_script "$@" 2>&1)"; then
		status=0
	else
		status=$?
	fi
	if [[ $status -ne 0 ]] && printf '%s\n' "$output" | grep -Fq -- "$expected"; then
		self_test_pass "$name"
	else
		self_test_fail "$name"
	fi
}

self_test_version_options() {
	local help_text
	if [[ "$PRE_DB_USER_VERSION" == "$PRE_DB_USER_VERSION_DEFAULT" &&
		"$POST_DB_USER_VERSION" == "$POST_DB_USER_VERSION_DEFAULT" &&
		"$PRE_DB_USER_VERSION_DEFAULT" == "10" &&
		"$POST_DB_USER_VERSION_DEFAULT" == "11" ]]; then
		self_test_pass "default DB user versions are distinct 10 and 11"
	else
		self_test_fail "default DB user versions were $PRE_DB_USER_VERSION->$POST_DB_USER_VERSION"
	fi

	help_text="$(self_test_run_script --help)"
	if printf '%s\n' "$help_text" | grep -Fq -- '--pre-db-user-version' &&
		printf '%s\n' "$help_text" | grep -Fq -- '--post-db-user-version' &&
		printf '%s\n' "$help_text" | grep -Fq 'defaults 10 and 11' &&
		! printf '%s\n' "$help_text" | grep -Eq -- '--db-user-version N'; then
		self_test_pass "help describes distinct pre/post DB user versions"
	else
		self_test_fail "help does not describe distinct pre/post DB user versions"
	fi

	self_test_expect_error "single --db-user-version is rejected" \
		'not accepted because a single user_version cannot describe the 10->11 migration' \
		--db-user-version 10 --self-test
	self_test_expect_error "non-integer --pre-db-user-version is rejected" \
		'--pre-db-user-version must be an integer' \
		--pre-db-user-version abc --self-test
	self_test_expect_error "non-integer --post-db-user-version is rejected" \
		'--post-db-user-version must be an integer' \
		--post-db-user-version abc --self-test
	self_test_expect_error "post DB user version must be greater than pre" \
		'candidate DB user_version must be greater than archived DB user_version' \
		--pre-db-user-version 10 --post-db-user-version 10 --self-test
	self_test_expect_error "missing --pre-db-user-version value is rejected" \
		'--pre-db-user-version requires a value' \
		--pre-db-user-version

	if validate_db_user_versions; then
		self_test_pass "validate_db_user_versions accepts 10 then 11"
	else
		self_test_fail "validate_db_user_versions rejected 10 then 11"
	fi
}

self_test_write_xml_prefs() {
	local destination="$1"
	local complete="${2:-0}"
	write_seed_preferences "$destination"
	if [[ "$complete" == "1" ]]; then
		cat >"$destination/transaction_location_backfill.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="$BACKFILL_COMPLETION_PREF_NAME" value="true" />
</map>
EOF
		chmod 600 "$destination/transaction_location_backfill.xml"
	fi
}

self_test_create_v10_db() {
	local db="$1"
	rm -f "$db"
	"$SQLITE3_BIN" -batch -bail "$db" <<SQL
PRAGMA user_version=$PRE_DB_USER_VERSION_DEFAULT;
CREATE TABLE transactions (
  id TEXT NOT NULL PRIMARY KEY,
  occurredAtEpochMillis INTEGER NOT NULL,
  merchant TEXT NOT NULL,
  category TEXT NOT NULL,
  note TEXT NOT NULL,
  cents INTEGER NOT NULL,
  recurringInterval TEXT,
  source TEXT NOT NULL DEFAULT 'local',
  accountKey TEXT,
  accountName TEXT,
  reviewedAtEpochMillis INTEGER,
  providerDescription TEXT,
  merchantOverride TEXT,
  flowKind TEXT NOT NULL DEFAULT 'NORMAL',
  flowKindOverride TEXT
);
CREATE TABLE simplefin_profile (
  id TEXT NOT NULL DEFAULT 'default' PRIMARY KEY,
  connectionId TEXT NOT NULL DEFAULT 'legacy',
  connectedAtEpochMillis INTEGER,
  lastSyncAttemptAtEpochMillis INTEGER,
  lastSuccessfulSyncAtEpochMillis INTEGER,
  lastError TEXT,
  isPaused INTEGER NOT NULL DEFAULT 0,
  automaticSyncsPerDay INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE simplefin_accounts (
  accountId TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  currency TEXT,
  institutionName TEXT,
  balanceAmount TEXT,
  availableBalanceAmount TEXT,
  balanceDateEpochSeconds INTEGER,
  lastSeenAtEpochMillis INTEGER NOT NULL
);
CREATE TABLE simplefin_ignored_transactions (
  transactionId TEXT NOT NULL PRIMARY KEY,
  ignoredAtEpochMillis INTEGER NOT NULL,
  occurredAtEpochMillis INTEGER
);
CREATE TABLE simplefin_identity_state (
  id TEXT NOT NULL DEFAULT 'stable_v2' PRIMARY KEY,
  reconciliationComplete INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE merchant_rules (
  normalizedProviderMerchant TEXT NOT NULL PRIMARY KEY,
  category TEXT NOT NULL,
  merchantOverride TEXT
);
INSERT INTO transactions
    (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source,
     accountKey, accountName, providerDescription, flowKind)
VALUES
    ('$LOCAL_EXPENSE_TX_ID', 1, 'Rehearsal Market', 'Groceries', 'marker', -1234, 'Monthly', 'local',
     NULL, NULL, NULL, 'NORMAL'),
    ('$LOCAL_INCOME_TX_ID', 2, 'Rehearsal Payroll', 'Salary', 'marker', 250000, NULL, 'local',
     NULL, NULL, NULL, 'NORMAL'),
    ('$PARSEABLE_TX_ID', 3, 'Rehearsal Cafe', 'Coffee', 'marker', -5678, NULL, 'simplefin',
     'upgrade-account-001', 'Upgrade Checking', '$PARSEABLE_PROVIDER_DESCRIPTION', 'NORMAL'),
    ('$UNPARSEABLE_TX_ID', 4, 'Rehearsal Cafe Store', 'Coffee', 'marker', -4321, NULL, 'simplefin',
     'upgrade-account-001', 'Upgrade Checking', '$UNPARSEABLE_PROVIDER_DESCRIPTION', 'NORMAL');
INSERT INTO simplefin_profile (id, connectionId, lastError, isPaused, automaticSyncsPerDay)
VALUES ('default', 'upgrade-rehearsal-connection', 'paused', 1, 4);
INSERT INTO simplefin_accounts (accountId, name, currency, institutionName, lastSeenAtEpochMillis)
VALUES ('upgrade-account-001', 'Upgrade Checking', 'USD', 'Rehearsal Credit Union', 1);
INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis)
VALUES ('upgrade-ignored-transaction', 1);
INSERT INTO merchant_rules (normalizedProviderMerchant, category, merchantOverride)
VALUES ('$MERCHANT_RULE_KEY', 'Coffee', 'Rehearsal Cafe');
SQL
}

self_test_migrate_to_v11() {
	local db="$1"
	"$SQLITE3_BIN" -batch -bail "$db" <<SQL
PRAGMA user_version=$POST_DB_USER_VERSION_DEFAULT;
ALTER TABLE transactions ADD COLUMN locationCity TEXT;
ALTER TABLE transactions ADD COLUMN locationState TEXT;
ALTER TABLE transactions ADD COLUMN locationCountry TEXT;
CREATE TABLE IF NOT EXISTS place_geocodes (
  placeKey TEXT NOT NULL,
  city TEXT,
  state TEXT,
  country TEXT,
  latitude REAL,
  longitude REAL,
  resolvedAtEpochMillis INTEGER NOT NULL,
  PRIMARY KEY(placeKey)
);
UPDATE transactions
SET locationCity='$EXPECTED_BACKFILL_CITY', locationState='$EXPECTED_BACKFILL_STATE', locationCountry=NULL
WHERE id='$PARSEABLE_TX_ID';
SQL
}

self_test_write_snapshot() {
	local snapshot="$1"
	local db="$2"
	local complete="$3"
	mkdir -p "$snapshot/canonical/db-working" "$snapshot/raw/shared_prefs"
	cp "$db" "$snapshot/canonical/db-working/flow_money.db"
	dump_preexisting_logical "$snapshot/canonical/db-working/flow_money.db" \
		"$snapshot/canonical/database-logical.txt"
	dump_schema_contract "$snapshot/canonical/db-working/flow_money.db" \
		"$snapshot/canonical/database-schema.txt"
	self_test_write_xml_prefs "$snapshot/raw/shared_prefs" "$complete"
	cat >"$snapshot/canonical/fingerprint-components.txt" <<'EOF'
database_logical_sha256=TEST
flow_money_preferences_sha256=TEST
simplefin_cleanup_preferences_sha256=TEST
files_manifest=MISSING
simplefin_credential=MISSING
widget_binding_sha256=TEST
EOF
	chmod 600 "$snapshot/canonical/fingerprint-components.txt"
}

self_test_delta_case() {
	local test_root="$1"
	local name="$2"
	local expectation="$3"
	local mutator="$4"
	local expected_message="${5:-}"
	local work="$test_root/delta-$name"
	local output status
	rm -rf "$work"
	mkdir -p "$work"
	self_test_create_v10_db "$work/v10.db"
	cp "$work/v10.db" "$work/v11.db"
	self_test_migrate_to_v11 "$work/v11.db"
	self_test_write_snapshot "$work/10-archived" "$work/v10.db" 0
	self_test_write_snapshot "$work/20-candidate" "$work/v11.db" 1
	if [[ -n "$mutator" ]]; then
		"$mutator" "$work"
	fi
	if (
		assert_upgrade_delta "$work/10-archived" "$work/20-candidate"
	) >"$work/output.log" 2>&1; then
		status=0
	else
		status=$?
	fi
	chmod 600 "$work/output.log"
	if [[ "$expectation" == "pass" ]]; then
		if [[ $status -eq 0 ]]; then
			self_test_pass "delta logic accepts $name"
		else
			self_test_fail "delta logic rejected $name"
		fi
	elif [[ $status -ne 0 ]] && grep -Fq "$expected_message" "$work/output.log"; then
		self_test_pass "delta logic rejects $name"
	else
		self_test_fail "delta logic did not reject $name with: $expected_message"
	fi
}

self_test_mutate_extra_column() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		'ALTER TABLE transactions ADD COLUMN sneaky TEXT;'
	dump_schema_contract "$1/20-candidate/canonical/db-working/flow_money.db" \
		"$1/20-candidate/canonical/database-schema.txt"
}

self_test_mutate_extra_table() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		'CREATE TABLE sneaky_table (id TEXT PRIMARY KEY);'
	dump_schema_contract "$1/20-candidate/canonical/db-working/flow_money.db" \
		"$1/20-candidate/canonical/database-schema.txt"
}

self_test_mutate_logical() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"UPDATE transactions SET merchant='tampered' WHERE id='$LOCAL_EXPENSE_TX_ID';"
	dump_preexisting_logical "$1/20-candidate/canonical/db-working/flow_money.db" \
		"$1/20-candidate/canonical/database-logical.txt"
}

self_test_mutate_missing_backfill() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"UPDATE transactions SET locationCity=NULL, locationState=NULL, locationCountry=NULL WHERE id='$PARSEABLE_TX_ID';"
}

self_test_mutate_unparseable_located() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"UPDATE transactions SET locationCity='SEATTLE', locationState='WA' WHERE id='$UNPARSEABLE_TX_ID';"
}

self_test_mutate_local_located() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"UPDATE transactions SET locationCity='AUSTIN' WHERE id='$LOCAL_EXPENSE_TX_ID';"
}

self_test_mutate_inferred_country() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"UPDATE transactions SET locationCountry='US' WHERE id='$PARSEABLE_TX_ID';"
}

self_test_mutate_missing_completion() {
	rm -f "$1/20-candidate/raw/$BACKFILL_COMPLETION_PREFS_RELATIVE"
}

self_test_mutate_fingerprint() {
	printf 'tampered\n' >"$1/20-candidate/canonical/fingerprint-components.txt"
	chmod 600 "$1/20-candidate/canonical/fingerprint-components.txt"
}

self_test_mutate_geocode_row() {
	"$SQLITE3_BIN" -batch -bail "$1/20-candidate/canonical/db-working/flow_money.db" \
		"INSERT INTO place_geocodes (placeKey, city, state, country, latitude, longitude, resolvedAtEpochMillis) VALUES ('austin|tx|', 'AUSTIN', 'TX', NULL, 30.27, -97.74, 1);"
}

self_test_mutate_pre_location_columns() {
	"$SQLITE3_BIN" -batch -bail "$1/10-archived/canonical/db-working/flow_money.db" <<'SQL'
ALTER TABLE transactions ADD COLUMN locationCity TEXT;
ALTER TABLE transactions ADD COLUMN locationState TEXT;
ALTER TABLE transactions ADD COLUMN locationCountry TEXT;
SQL
	dump_schema_contract "$1/10-archived/canonical/db-working/flow_money.db" \
		"$1/10-archived/canonical/database-schema.txt"
}

self_test_delta_logic() {
	local test_root="$1"
	self_test_delta_case "$test_root" expected-10-11 pass ""
	self_test_delta_case "$test_root" extra-column reject self_test_mutate_extra_column \
		'unexpected changes besides location columns and place_geocodes'
	self_test_delta_case "$test_root" extra-table reject self_test_mutate_extra_table \
		'unexpected changes besides location columns and place_geocodes'
	self_test_delta_case "$test_root" logical-tamper reject self_test_mutate_logical \
		'Pre-existing logical database values are not unchanged'
	self_test_delta_case "$test_root" missing-backfill reject self_test_mutate_missing_backfill \
		'parseable SimpleFIN backfill'
	self_test_delta_case "$test_root" unparseable-located reject self_test_mutate_unparseable_located \
		'unexpected located row'
	self_test_delta_case "$test_root" local-located reject self_test_mutate_local_located \
		'unexpected located row'
	self_test_delta_case "$test_root" inferred-country reject self_test_mutate_inferred_country \
		'parseable SimpleFIN backfill'
	self_test_delta_case "$test_root" missing-completion reject self_test_mutate_missing_completion \
		'Location backfill completion preference is missing or not true'
	self_test_delta_case "$test_root" fingerprint-tamper reject self_test_mutate_fingerprint \
		'Data/settings/files/credential/widget canonical fingerprint mismatch'
	self_test_delta_case "$test_root" geocode-row reject self_test_mutate_geocode_row \
		'place_geocodes has 1 unexpected row'
	self_test_delta_case "$test_root" pre-has-location reject self_test_mutate_pre_location_columns \
		'already has transactions.locationCity'
}

self_test_backfill_wait() {
	local test_root="$1"
	local output status attempt_dir

	if (
		BACKFILL_POLL_TIMEOUT_SECONDS=3
		BACKFILL_POLL_INTERVAL_SECONDS=0
		attempt_dir=0
		fetch_remote_backfill_preference() {
			attempt_dir=$((attempt_dir + 1))
			if ((attempt_dir >= 2)); then
				cat >"$1" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="$BACKFILL_COMPLETION_PREF_NAME" value="true" />
</map>
EOF
				chmod 600 "$1"
				return 0
			fi
			return 1
		}
		wait_for_location_backfill_completion "$test_root/wait-success/backfill-poll"
	) >"$test_root/wait-success.log" 2>&1; then
		self_test_pass "backfill wait observes completion before timeout"
	else
		self_test_fail "backfill wait did not observe a later completion preference"
	fi
	chmod 600 "$test_root/wait-success.log"

	if (
		BACKFILL_POLL_TIMEOUT_SECONDS=1
		BACKFILL_POLL_INTERVAL_SECONDS=1
		fetch_remote_backfill_preference() { return 1; }
		wait_for_location_backfill_completion "$test_root/wait-timeout/backfill-poll"
	) >"$test_root/wait-timeout.log" 2>&1; then
		status=0
	else
		status=$?
	fi
	chmod 600 "$test_root/wait-timeout.log"
	if [[ $status -ne 0 ]] && grep -Fq 'Timed out after 1s waiting for location backfill completion preference' \
		"$test_root/wait-timeout.log"; then
		self_test_pass "backfill wait fails closed when completion never appears"
	else
		self_test_fail "backfill wait did not fail closed on timeout"
	fi
}

run_self_tests() {
	local test_root helper_output target mode
	test_root="$(mktemp -d "${TMPDIR:-/tmp}/penny-upgrade-self-test.XXXXXX")"
	chmod 700 "$test_root"
	mkdir -p "$test_root/tools" "$test_root/path" "$test_root/empty-sdk" "$test_root/non-executable"
	chmod 700 "$test_root/tools" "$test_root/path" "$test_root/empty-sdk" "$test_root/non-executable"
	ln -s "$(command -v awk)" "$test_root/path/awk"
	if command -v sha256sum >/dev/null 2>&1; then
		ln -s "$(command -v sha256sum)" "$test_root/path/sha256sum"
	else
		ln -s "$(command -v shasum)" "$test_root/path/shasum"
	fi
	for target in adb apkanalyzer apksigner sqlite3 python3; do
		make_mock_executable "$test_root/tools/$target"
	done
	for target in ADB APKANALYZER APKSIGNER SQLITE3 PYTHON3; do
		printf 'not executable\n' >"$test_root/non-executable/$target"
		chmod 600 "$test_root/non-executable/$target"
	done

	if helper_output="$(command_or_path "$test_root/missing/not-executable")" && [[ -z "$helper_output" ]]; then
		self_test_pass "command_or_path returns successful empty output for a missing path"
	else
		self_test_fail "command_or_path failed instead of returning empty output"
	fi
	if helper_output="$(latest_build_tool penny-self-test-tool-does-not-exist)" && [[ -z "$helper_output" ]]; then
		self_test_pass "latest_build_tool returns successful empty output when no executable exists"
	else
		self_test_fail "latest_build_tool failed instead of returning empty output"
	fi

	for target in ADB APKANALYZER APKSIGNER SQLITE3 PYTHON3; do
		for mode in override fallback non-executable; do
			self_test_missing_tool_case "$test_root" "$target" "$mode"
		done
	done

	self_test_preflight_case "$test_root" modern pass "" emulator-5554 device "" 1 emulator ranchu ranchu $'FlowMoney_API_35\r\nOK\r\n' 0 0
	self_test_preflight_case "$test_root" legacy pass "" emulator-5556 device 1 1 emulator goldfish "" $'Legacy_API_26\nOK\n' 0 0
	self_test_preflight_case "$test_root" physical-serial reject "Serial physical-123 is not an Android Emulator serial" physical-123 device 1 1 emulator ranchu ranchu $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" kernel-spoof reject "ro.kernel.qemu is present but not 1" emulator-5554 device 0 1 emulator ranchu ranchu $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" boot-qemu-spoof reject "ro.boot.qemu is not 1" emulator-5554 device "" 0 emulator ranchu ranchu $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" characteristics-spoof reject "ro.build.characteristics lacks an exact emulator characteristic" emulator-5554 device "" 1 phone,emulatorish ranchu ranchu $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" hardware-spoof reject "ro.hardware is not a recognized ranchu/goldfish emulator family" emulator-5554 device "" 1 emulator qcom qcom $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" boot-hardware-spoof reject "ro.boot.hardware is not a recognized ranchu/goldfish emulator family" emulator-5554 device "" 1 emulator ranchu qcom $'Spoof\nOK\n' 0 0
	self_test_preflight_case "$test_root" console-spoof reject "Emulator console AVD identity query failed" emulator-5554 device "" 1 emulator ranchu ranchu $'Spoof\nOK\n' 1 0
	self_test_preflight_case "$test_root" malformed-avd reject "Emulator console returned a malformed AVD identity" emulator-5554 device "" 1 emulator ranchu ranchu $'Spoof\nKO\n' 0 0
	self_test_preflight_case "$test_root" secondary-user reject "Current Android user is 10" emulator-5554 device "" 1 emulator ranchu ranchu $'FlowMoney_API_35\nOK\n' 0 10

	self_test_ui_capture "$test_root"
	self_test_version_options

	SQLITE3_BIN="$(command -v sqlite3 || true)"
	if [[ -x "$SQLITE3_BIN" ]]; then
		self_test_delta_logic "$test_root"
		self_test_backfill_wait "$test_root"
	else
		self_test_fail "sqlite3 is required for version-delta self-tests"
	fi
	rm -rf "$test_root"
	if [[ $SELF_TEST_FAILURE_COUNT -ne 0 ]]; then
		die "$SELF_TEST_FAILURE_COUNT mock self-test(s) failed"
	fi
	log "All mock self-tests passed"
}

if [[ $SELF_TEST -eq 1 ]]; then
	run_self_tests
	COMPLETED=1
	exit 0
fi

prepare_rehearsal
install_archived
verify_installed_apk "$OLD_APK" "$OLD_VERSION_NAME" "$OLD_VERSION_CODE" \
	"$OUTPUT_DIR/00-archived-installed-apk" "$OLD_CERT"
launch_app

# run-as is essential: archived hand-release debug APKs are expected to be debuggable.
shell_cmd run-as "$PACKAGE" id >/dev/null 2>&1 ||
	die "run-as failed. The archived APK must be debuggable for a faithful snapshot rehearsal."
shell_cmd run-as "$PACKAGE" tar --help >/dev/null 2>&1 ||
	die "The emulator run-as environment does not provide tar"

if [[ $WITH_SIMPLEFIN_CREDENTIAL -eq 1 ]]; then
	confirm_exact "CREDENTIAL-STORED" \
		"Inside Penny on the emulator, connect SimpleFIN now. Enter the setup token only in the app; never in this terminal. Wait for connection success."
	force_stop_app
	remote_exists "no_backup/simplefin_access_url.bin" ||
		die "Encrypted SimpleFIN credential file was not created"
else
	force_stop_app
fi

capture_snapshot "00-archived-initial" optional
seed_database_and_preferences
launch_app
force_stop_app

# Verify seeded state before asking the launcher to bind a widget.
capture_snapshot "01-archived-seeded-prewidget" required
sqlite_quick_check "$OUTPUT_DIR/01-archived-seeded-prewidget/raw/databases" \
	"$OUTPUT_DIR/01-archived-seeded-prewidget/quick-check-working" >/dev/null

shell_cmd input keyevent HOME >/dev/null
confirm_exact "WIDGET-BOUND" \
	"Using the emulator launcher, add exactly one Penny 2x2 summary widget to the visible home screen. Confirm that it renders the seeded current-month summary."
capture_widget_record "$OUTPUT_DIR/10-archived/widget"
capture_screen "$OUTPUT_DIR/10-archived/widget/baseline-widget.png"
force_stop_app
capture_snapshot "10-archived" required
create_canonical_fingerprint "10-archived" "$OUTPUT_DIR/10-archived/widget/widget-canonical.txt" \
	"$PRE_DB_USER_VERSION"

BASELINE_UID="$(package_uid)"
confirm_exact "REPLACE-1.0.13-WITH-1.0.14" \
	"Baseline artifacts are complete. The next and only install command is adb -s $SERIAL install -r NEW_APK. Stop now if any baseline check is uncertain."

install_candidate_replacement_only
verify_installed_apk "$NEW_APK" "$NEW_VERSION_NAME" "$NEW_VERSION_CODE" \
	"$OUTPUT_DIR/20-candidate/installed-apk" "$NEW_CERT"
[[ "$(package_uid)" == "$BASELINE_UID" ]] || die "Package UID changed across replacement; fail closed"

# Binding must survive replacement before the candidate gets a first launch.
capture_widget_record "$OUTPUT_DIR/20-candidate/widget-postinstall"
cmp -s "$OUTPUT_DIR/10-archived/widget/widget-canonical.txt" \
	"$OUTPUT_DIR/20-candidate/widget-postinstall/widget-canonical.txt" ||
	die "Widget binding changed during adb install -r"

launch_app
wait_for_location_backfill_completion
shell_cmd input keyevent HOME >/dev/null
sleep 1
capture_screen "$OUTPUT_DIR/20-candidate/widget-postlaunch.png"
verify_widget_routes
force_stop_app
capture_widget_record "$OUTPUT_DIR/20-candidate/widget-final"
cmp -s "$OUTPUT_DIR/10-archived/widget/widget-canonical.txt" \
	"$OUTPUT_DIR/20-candidate/widget-final/widget-canonical.txt" ||
	die "Widget binding changed after candidate launch/routes"
capture_snapshot "20-candidate" required
create_canonical_fingerprint "20-candidate" "$OUTPUT_DIR/20-candidate/widget-final/widget-canonical.txt" \
	"$POST_DB_USER_VERSION"
assert_upgrade_delta "$OUTPUT_DIR/10-archived" "$OUTPUT_DIR/20-candidate"

cat >"$OUTPUT_DIR/result.txt" <<EOF
PASS
package=$PACKAGE
transition=$OLD_VERSION_NAME/$OLD_VERSION_CODE->$NEW_VERSION_NAME/$NEW_VERSION_CODE
db_user_version=$PRE_DB_USER_VERSION->$POST_DB_USER_VERSION
schema_delta=transactions.locationCity,locationState,locationCountry;place_geocodes
backfill_delta=parseable=$EXPECTED_BACKFILL_CITY/$EXPECTED_BACKFILL_STATE/null;unparseable=null;local=untouched
completion_preference=$BACKFILL_COMPLETION_PREF_NAME
migration_backfill=validated
certificate_sha256=$NEW_CERT
uid=$BASELINE_UID
sqlite_quick_check=ok
canonical_fingerprint=$(cat "$OUTPUT_DIR/20-candidate/canonical/fingerprint.sha256")
widget_binding=preserved
widget_routes=overview,add_transaction
simplefin_credential=$([[ -f "$OUTPUT_DIR/20-candidate/raw/no_backup/simplefin_access_url.bin" ]] && printf 'encrypted-file-preserved' || printf 'not-seeded')
EOF
chmod 600 "$OUTPUT_DIR/result.txt"

COMPLETED=1
log "PASS: replacement-only $PRE_DB_USER_VERSION->$POST_DB_USER_VERSION upgrade preserved pre-existing data and validated the location migration/backfill."
log "Sensitive artifacts: $OUTPUT_DIR"
log "The encrypted SimpleFIN blob is not portable without the original Android Keystore key; never test restoration by uninstalling."
