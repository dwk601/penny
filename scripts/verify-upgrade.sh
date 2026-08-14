#!/usr/bin/env bash
# Rehearse Penny's v1.0.13 -> v1.0.14 replacement upgrade on an Android emulator.
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
readonly DB_USER_VERSION_DEFAULT="6"

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
DB_USER_VERSION="$DB_USER_VERSION_DEFAULT"
WITH_SIMPLEFIN_CREDENTIAL=0
DRY_RUN=0
COMPLETED=0
DEVICE_MUTATED=0

ADB_BIN=""
APKANALYZER_BIN=""
APKSIGNER_BIN=""
SQLITE3_BIN=""
PYTHON3_BIN=""
SHA256_BIN=""
SHA256_STYLE=""

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

Options:
  --old-version-code N       Expected archived versionCode (default: 113)
  --new-version-code N       Expected candidate versionCode (default: 114)
  --db-user-version N        Expected Room/SQLite user_version (default: 6)
  --with-simplefin-credential
                             Pause after the archived app starts so the operator
                             can connect SimpleFIN inside the app. No token or
                             access URL is accepted by or printed from this script.
  --dry-run                  Read-only tool/device/APK preflight. With no other
                             arguments, print the resolved tools and plan only.
  -h, --help                 Show this help without touching a device.

Environment overrides:
  ADB, APKANALYZER, APKSIGNER, SQLITE3, PYTHON3

Interactive steps:
  The operator must confirm an AVD snapshot, optionally connect SimpleFIN in the
  app, add exactly one Penny widget through the launcher, and visually attest
  the widget body (Overview) and plus-button (Add transaction) routes. Generic
  ADB has no portable launcher widget-allocation API, so the script verifies the
  binding plus page-specific selected-tab/editor semantics instead of treating
  always-visible navigation or FAB labels as route proof.

Artifacts:
  The output directory is mode 0700 and contains mode-0600 run-as snapshots,
  including the DB/WAL/SHM when present, both preference files, files/,
  no_backup/, and simplefin_access_url.bin when present. It can contain private
  financial data and encrypted credential material; store and delete it safely.
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
	--db-user-version)
		require_value "$1" "${2:-}"
		DB_USER_VERSION="$2"
		shift 2
		;;
	--with-simplefin-credential)
		WITH_SIMPLEFIN_CREDENTIAL=1
		shift
		;;
	--dry-run)
		DRY_RUN=1
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
[[ "$DB_USER_VERSION" =~ ^[0-9]+$ ]] || die "--db-user-version must be an integer"
((NEW_VERSION_CODE > OLD_VERSION_CODE)) || die "candidate versionCode must be greater than archived versionCode"

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
		[[ -x "$requested" ]] && printf '%s\n' "$requested"
	else
		command -v "$requested" 2>/dev/null || true
	fi
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
	[[ -n "$best" ]] && printf '%s\n' "$best"
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
	local digests
	digests="$("$APKSIGNER_BIN" verify --print-certs "$apk" |
		awk -F': ' '/certificate SHA-256 digest:/ {print toupper($2)}' |
		tr -d '\r' |
		sort -u)"
	[[ -n "$digests" ]] || die "No signing certificate SHA-256 digest found for $apk"
	[[ "$(printf '%s\n' "$digests" | awk 'NF {count++} END {print count+0}')" == "1" ]] ||
		die "Exactly one APK signer is required: $apk"
	[[ "$digests" =~ ^[0-9A-F]{64}$ ]] || die "Malformed signing certificate digest for $apk"
	printf '%s\n' "$digests"
}

apk_metadata() {
	local apk="$1"
	printf '%s\t%s\t%s\t%s\n' \
		"$(apk_manifest_value "$apk" application-id)" \
		"$(apk_manifest_value "$apk" version-name)" \
		"$(apk_manifest_value "$apk" version-code)" \
		"$(apk_cert_digest "$apk")"
}

OLD_METADATA=""
NEW_METADATA=""
OLD_CERT=""
NEW_CERT=""

preflight_apks() {
	[[ -f "$OLD_APK" && -r "$OLD_APK" ]] || die "Archived APK is not a readable file: $OLD_APK"
	[[ -f "$NEW_APK" && -r "$NEW_APK" ]] || die "Candidate APK is not a readable file: $NEW_APK"

	OLD_METADATA="$(apk_metadata "$OLD_APK")"
	NEW_METADATA="$(apk_metadata "$NEW_APK")"

	local old_package old_name old_code new_package new_name new_code
	IFS=$'\t' read -r old_package old_name old_code OLD_CERT <<<"$OLD_METADATA"
	IFS=$'\t' read -r new_package new_name new_code NEW_CERT <<<"$NEW_METADATA"

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
	# adb shell joins argv before the device shell parses it. Keep the complete
	# nested sh -c invocation in one argument so redirections/tests are not lost.
	shell_cmd "run-as '$PACKAGE' sh -c \"$command\""
}

trim_device_output() {
	tr -d '\r' | tail -n 1
}

preflight_emulator() {
	[[ -n "$SERIAL" ]] || die "--serial is required; implicit adb device selection is forbidden"
	[[ "$SERIAL" == emulator-* ]] || die "Serial $SERIAL is not an Android Emulator serial (expected emulator-*)"

	local state kernel_qemu boot_qemu characteristics current_user
	state="$(adb_cmd get-state 2>/dev/null | trim_device_output || true)"
	[[ "$state" == "device" ]] || die "Emulator $SERIAL is not online (state: ${state:-missing})"
	kernel_qemu="$(shell_cmd getprop ro.kernel.qemu | trim_device_output)"
	boot_qemu="$(shell_cmd getprop ro.boot.qemu | trim_device_output)"
	characteristics="$(shell_cmd getprop ro.build.characteristics | trim_device_output)"
	current_user="$(shell_cmd am get-current-user | trim_device_output)"

	[[ "$kernel_qemu" == "1" ]] || die "ro.kernel.qemu is not 1; refusing possible physical device"
	[[ "$boot_qemu" == "1" ]] || die "ro.boot.qemu is not 1; refusing possible physical device"
	[[ ",$characteristics," == *,emulator,* || "$characteristics" == *emulator* ]] ||
		die "ro.build.characteristics does not identify an emulator"
	[[ "$current_user" == "0" ]] || die "Current Android user is $current_user; this rehearsal requires user 0"

	log "Positively identified online emulator $SERIAL (QEMU user 0)"
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
  2. Snapshot, seed deterministic DB rows and both preference contracts, and verify quick_check.
  3. Require one launcher-bound Penny widget and capture the baseline canonical fingerprint,
     using a sorted file type/content manifest rather than tar metadata for files/.
  4. Run exactly: adb -s SERIAL install -r NEW_APK
  5. Fail closed on package, certificate, version, UID, widget, quick_check, or fingerprint mismatch.
  6. Require visual checks of both widget routes and retain secure before/after run-as snapshots.
EOF
}

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

confirm_exact() {
	local expected="$1"
	local prompt="$2"
	local answer=""
	printf '\n%s\nType %s to continue: ' "$prompt" "$expected" >/dev/tty
	IFS= read -r answer </dev/tty
	[[ "$answer" == "$expected" ]] || die "Confirmation did not match $expected"
}

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
ro_kernel_qemu=$(shell_cmd getprop ro.kernel.qemu | trim_device_output)
ro_boot_qemu=$(shell_cmd getprop ro.boot.qemu | trim_device_output)
EOF
chmod 600 "$OUTPUT_DIR/apk-preflight.txt"

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

	metadata="$(apk_metadata "$pulled")"
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
	capture_remote_dir "files" "$raw/files.tar" optional "$manifest"
	capture_remote_dir "no_backup" "$raw/no_backup.tar" optional "$manifest"
	capture_remote_file "no_backup/simplefin_access_url.bin" \
		"$raw/no_backup/simplefin_access_url.bin" optional "$manifest"
	log "Captured secure run-as snapshot: $name"
}

install_private_file() {
	local source="$1"
	local relative="$2"
	local temporary command
	[[ -f "$source" && -r "$source" ]] || die "Private seed source is not readable: $source"

	# Stream bytes directly into app-private storage. Never stage seed data in
	# /data/local/tmp, where another shell process could read it between push,
	# chmod, and cleanup. Use an app-private temporary for atomic replacement.
	temporary="${relative}.penny-upgrade-${$}.part"
	command="umask 077; rm -f '$temporary'; cat > '$temporary' && chmod 600 '$temporary' && mv -f '$temporary' '$relative'"
	if ! adb_cmd exec-out "run-as '$PACKAGE' sh -c \"$command\"" <"$source" >/dev/null; then
		run_as_sh "rm -f '$temporary'" >/dev/null 2>&1 || true
		die "Could not stream run-as file into app-private storage: $relative"
	fi
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
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA user_version;' | tr -d '\r')" == "$DB_USER_VERSION" ]] ||
		die "Archived database user_version is not $DB_USER_VERSION"

	now_ms=$(($(date +%s) * 1000))
	expense_ms=$((now_ms - 86400000))
	income_ms=$((now_ms - 172800000))

	"$SQLITE3_BIN" -batch -bail "$seed_db" <<SQL >/dev/null
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
INSERT INTO transactions
    (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source, accountKey, accountName)
VALUES
    ('upgrade-local-expense', $expense_ms, 'Rehearsal Market', 'Groceries', 'v1.0.13 replacement marker', -1234, 'monthly', 'local', NULL, NULL),
    ('upgrade-local-income', $income_ms, 'Rehearsal Payroll', 'Salary', 'v1.0.13 replacement marker', 250000, NULL, 'local', NULL, NULL),
    ('upgrade-simplefin-transaction', $expense_ms, 'Rehearsal SimpleFIN Merchant', 'Shopping', 'v1.0.13 replacement marker', -5678, NULL, 'simplefin', 'upgrade-account-001', 'Upgrade Checking');
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
COMMIT;
PRAGMA wal_checkpoint(FULL);
SQL

	local marker_counts
	marker_counts="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$seed_db" \
		"SELECT (SELECT count(*) FROM transactions WHERE id LIKE 'upgrade-%'), (SELECT count(*) FROM simplefin_profile WHERE id='default'), (SELECT count(*) FROM simplefin_accounts WHERE accountId='upgrade-account-001'), (SELECT count(*) FROM simplefin_ignored_transactions WHERE transactionId='upgrade-ignored-transaction');" |
		tr -d '\r')"
	[[ "$marker_counts" == "3|1|1|1" ]] || die "Seed marker verification failed (expected 3|1|1|1, got $marker_counts)"
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA quick_check;' | tr -d '\r')" == "ok" ]] ||
		die "Seed database quick_check failed"

	# Collapse the stopped app's coherent DB/WAL state into one main database before
	# replacing the initial empty database. The initial sidecars are already captured.
	"$SQLITE3_BIN" -batch -bail "$seed_db" 'PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;' >/dev/null
	[[ "$("$SQLITE3_BIN" -batch "$seed_db" 'PRAGMA quick_check;' | tr -d '\r')" == "ok" ]] ||
		die "Collapsed seed database quick_check failed"

	write_seed_preferences "$seed_dir/preferences"
	install_private_file "$seed_db" "databases/flow_money.db"
	shell_cmd run-as "$PACKAGE" rm -f "databases/flow_money.db-wal" "databases/flow_money.db-shm" >/dev/null
	install_private_file "$seed_dir/preferences/flow_money.xml" "shared_prefs/flow_money.xml"
	install_private_file "$seed_dir/preferences/simplefin_migration_cleanup.xml" \
		"shared_prefs/simplefin_migration_cleanup.xml"
	log "Seeded representative transactions, preferences, and SimpleFIN profile/account/ignored rows"
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
	local dir="$OUTPUT_DIR/$snapshot_name"
	local raw="$dir/raw"
	local canonical="$dir/canonical"
	local db="$canonical/db-working/flow_money.db"
	local credential_component="MISSING"
	local files_component="MISSING"
	mkdir -p "$canonical"

	sqlite_quick_check "$raw/databases" "$canonical/db-working" >"$canonical/sqlite-quick-check.txt"
	[[ "$("$SQLITE3_BIN" -batch "$db" 'PRAGMA user_version;' | tr -d '\r')" == "$DB_USER_VERSION" ]] ||
		die "$snapshot_name database user_version is not $DB_USER_VERSION"

	"$SQLITE3_BIN" -batch -bail "$db" >"$canonical/database-logical.txt" <<'SQL'
.mode quote
SELECT 'transactions', id, occurredAtEpochMillis, merchant, category, note, cents,
       recurringInterval, source, accountKey, accountName
FROM transactions ORDER BY id;
SELECT 'simplefin_profile', id, connectionId, connectedAtEpochMillis,
       lastSyncAttemptAtEpochMillis, lastSuccessfulSyncAtEpochMillis, lastError,
       isPaused, automaticSyncsPerDay
FROM simplefin_profile ORDER BY id;
SELECT 'simplefin_accounts', accountId, name, currency, institutionName,
       balanceAmount, availableBalanceAmount, balanceDateEpochSeconds, lastSeenAtEpochMillis
FROM simplefin_accounts ORDER BY accountId;
SELECT 'simplefin_ignored_transactions', transactionId, ignoredAtEpochMillis
FROM simplefin_ignored_transactions ORDER BY transactionId;
SQL
	chmod 600 "$canonical/database-logical.txt"

	local marker_counts
	marker_counts="$("$SQLITE3_BIN" -batch -noheader -separator '|' "$db" \
		"SELECT (SELECT count(*) FROM transactions WHERE id LIKE 'upgrade-%'), (SELECT count(*) FROM simplefin_profile WHERE id='default'), (SELECT count(*) FROM simplefin_accounts WHERE accountId='upgrade-account-001'), (SELECT count(*) FROM simplefin_ignored_transactions WHERE transactionId='upgrade-ignored-transaction');" |
		tr -d '\r')"
	[[ "$marker_counts" == "3|1|1|1" ]] || die "$snapshot_name seed rows are missing or duplicated: $marker_counts"

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

capture_ui_dump() {
	local destination="$1"
	local remote="/sdcard/penny-upgrade-window-${$}.xml"
	local _
	for _ in 1 2 3; do
		if shell_cmd uiautomator dump "$remote" >/dev/null 2>&1; then
			adb_cmd exec-out cat "$remote" >"$destination"
			shell_cmd rm -f "$remote" >/dev/null 2>&1 || true
			chmod 600 "$destination"
			[[ -s "$destination" ]] && return 0
		fi
		sleep 1
	done
	shell_cmd rm -f "$remote" >/dev/null 2>&1 || true
	die "Could not capture UI hierarchy"
}

ui_node_has_label() {
	local dump="$1"
	local label="$2"
	awk -v label="$label" '
        BEGIN { RS=">" }
        /<node[[:space:]]/ {
            if (index($0, "text=\"" label "\"") ||
                index($0, "content-desc=\"" label "\"")) found=1
        }
        END { exit(found ? 0 : 1) }
    ' "$dump"
}

ui_node_has_label_and_selected() {
	local dump="$1"
	local label="$2"
	local selected="$3"
	awk -v label="$label" -v selected="$selected" '
        BEGIN { RS=">" }
        /<node[[:space:]]/ {
            labeled = index($0, "text=\"" label "\"") ||
                      index($0, "content-desc=\"" label "\"")
            if (labeled && index($0, "selected=\"" selected "\"")) matches++
        }
        END { exit(matches == 1 ? 0 : 1) }
    ' "$dump"
}

overview_route_dump_is_specific() {
	local dump="$1"
	ui_node_has_label_and_selected "$dump" "Overview" true &&
		ui_node_has_label_and_selected "$dump" "Transactions" false &&
		ui_node_has_label_and_selected "$dump" "Insights" false
}

add_route_dump_is_specific() {
	local dump="$1"
	# "Add transaction" alone is the always-present FAB description. Require
	# fresh expense-editor-only controls and reject edit/income editor states.
	ui_node_has_label "$dump" "Add transaction" &&
		ui_node_has_label "$dump" "Amount paid" &&
		ui_node_has_label "$dump" "Save expense" &&
		! ui_node_has_label "$dump" "Edit transaction" &&
		! ui_node_has_label "$dump" "Income received" &&
		! ui_node_has_label "$dump" "Save income"
}

verify_widget_routes() {
	local route_dir="$OUTPUT_DIR/20-candidate/routes"
	mkdir -p "$route_dir"

	shell_cmd input keyevent HOME >/dev/null
	sleep 1
	confirm_exact "OVERVIEW-OK" \
		"Tap the Penny widget body (not +). Verify Penny opens with Overview selected."
	assert_foreground_activity
	capture_ui_dump "$route_dir/overview-window.xml"
	overview_route_dump_is_specific "$route_dir/overview-window.xml" ||
		die "Overview route lacked one selected Overview nav node and unselected Transactions/Insights nodes"
	capture_screen "$route_dir/overview-route.png"

	shell_cmd input keyevent HOME >/dev/null
	sleep 1
	confirm_exact "ADD-OK" \
		"Tap the Penny widget + button. Verify a fresh expense Add transaction editor opens."
	assert_foreground_activity
	capture_ui_dump "$route_dir/add-window.xml"
	add_route_dump_is_specific "$route_dir/add-window.xml" ||
		die "Add route lacked fresh expense-editor semantics (Amount paid and Save expense)"
	capture_screen "$route_dir/add-route.png"
	log "Both widget routes were operator-attested and page-specific UI/foreground-verified"
}

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
create_canonical_fingerprint "10-archived" "$OUTPUT_DIR/10-archived/widget/widget-canonical.txt"

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
create_canonical_fingerprint "20-candidate" "$OUTPUT_DIR/20-candidate/widget-final/widget-canonical.txt"

if ! cmp -s "$OUTPUT_DIR/10-archived/canonical/fingerprint-components.txt" \
	"$OUTPUT_DIR/20-candidate/canonical/fingerprint-components.txt"; then
	warn "Canonical fingerprint components differ (hashes only):"
	diff -u "$OUTPUT_DIR/10-archived/canonical/fingerprint-components.txt" \
		"$OUTPUT_DIR/20-candidate/canonical/fingerprint-components.txt" >&2 || true
	die "Data/settings/files/credential/widget canonical fingerprint mismatch"
fi

cat >"$OUTPUT_DIR/result.txt" <<EOF
PASS
package=$PACKAGE
transition=$OLD_VERSION_NAME/$OLD_VERSION_CODE->$NEW_VERSION_NAME/$NEW_VERSION_CODE
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
log "PASS: replacement-only upgrade preserved the canonical contract."
log "Sensitive artifacts: $OUTPUT_DIR"
log "The encrypted SimpleFIN blob is not portable without the original Android Keystore key; never test restoration by uninstalling."
