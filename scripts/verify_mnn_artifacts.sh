#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
apk_path="${2:-}"
generated_dir="${plugin_root}/.native/generated/arm64-v8a"
mnn_library="${generated_dir}/libMNN.so"
jni_library="${generated_dir}/libmnn_engine_jni.so"
build_info="${generated_dir}/mnn_build_info.json"
android_ndk="${ANDROID_NDK:-${HOME}/android-ndk-r27d}"
readelf_bin="${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

fail() {
    printf 'MNN artifact verification failed: %s\n' "$1" >&2
    exit 30
}

[[ -x "${readelf_bin}" ]] || fail "llvm-readelf was not found under ${android_ndk}"
[[ -s "${mnn_library}" ]] || fail "missing or empty ${mnn_library}"
[[ -s "${jni_library}" ]] || fail "missing or empty ${jni_library}"
[[ -s "${build_info}" ]] || fail "missing or empty ${build_info}"

verify_elf() {
    local artifact="$1"
    local header
    header="$("${readelf_bin}" -hW "${artifact}")"
    grep -q 'Class:[[:space:]]*ELF64' <<<"${header}" || fail "${artifact} is not ELF64"
    grep -q 'Type:[[:space:]]*DYN' <<<"${header}" || fail "${artifact} is not a shared object"
    grep -q 'Machine:[[:space:]]*AArch64' <<<"${header}" || fail "${artifact} is not AArch64"

    local load_count=0
    while read -r offset virtual_address alignment; do
        [[ -n "${alignment:-}" ]] || continue
        load_count=$((load_count + 1))
        local offset_value=$((offset))
        local address_value=$((virtual_address))
        local alignment_value=$((alignment))
        (( alignment_value >= 0x4000 )) || fail "${artifact} has LOAD alignment ${alignment}, expected at least 0x4000"
        (( (offset_value - address_value) % alignment_value == 0 )) ||
            fail "${artifact} has a LOAD segment whose offset and virtual address are not alignment-congruent"
    done < <("${readelf_bin}" -lW "${artifact}" | awk '$1 == "LOAD" { print $2, $3, $NF }')
    (( load_count > 0 )) || fail "${artifact} has no LOAD segments"
}

verify_elf "${mnn_library}"
verify_elf "${jni_library}"

"${readelf_bin}" -dW "${jni_library}" | grep -q 'Shared library: \[libMNN.so\]' ||
    fail "libmnn_engine_jni.so does not declare libMNN.so as a dependency"

required_symbols=(
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetVersion'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCreate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeGenerate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCancel'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeReset'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeRelease'
)
symbols="$("${readelf_bin}" -Ws "${jni_library}")"
for symbol in "${required_symbols[@]}"; do
    grep -q "${symbol}" <<<"${symbols}" || fail "missing JNI export ${symbol}"
done

python3 - "${build_info}" "${plugin_root}/MNN" <<'PY'
import json
import subprocess
import sys

build_info_path, mnn_root = sys.argv[1:]
with open(build_info_path, "r", encoding="utf-8") as stream:
    info = json.load(stream)

required = {
    "fingerprint",
    "mnnFingerprint",
    "mnnCommit",
    "ndkVersion",
    "cmakeVersion",
    "cmakePath",
    "ninjaVersion",
    "abi",
    "androidPlatform",
    "buildType",
    "nativeAdapterAbiVersion",
    "wslDistribution",
    "hostArchitecture",
    "cmakeFlags",
}
missing = sorted(required.difference(info))
if missing:
    raise SystemExit(f"mnn_build_info.json is missing fields: {', '.join(missing)}")
if info["abi"] != "arm64-v8a":
    raise SystemExit(f"unexpected ABI in build info: {info['abi']}")
if info["androidPlatform"] != "android-28":
    raise SystemExit(f"unexpected Android platform in build info: {info['androidPlatform']}")
if info["cmakeVersion"] != "3.22.1":
    raise SystemExit(f"unexpected CMake version in build info: {info['cmakeVersion']}")
if int(info["nativeAdapterAbiVersion"]) < 2:
    raise SystemExit("nativeAdapterAbiVersion must be at least 2")
flags = set(info["cmakeFlags"])
for expected in (
    "MNN_BUILD_FOR_ANDROID_COMMAND=ON",
    "MNN_BUILD_LLM_OMNI=ON",
    "MNN_KLEIDIAI=OFF",
    "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
):
    if expected not in flags:
        raise SystemExit(f"build info is missing required flag: {expected}")
commit = subprocess.check_output(
    ["git", "-C", mnn_root, "rev-parse", "HEAD"], text=True
).strip()
if info["mnnCommit"] != commit:
    raise SystemExit(
        f"build info MNN commit {info['mnnCommit']} does not match submodule {commit}"
    )
PY

if [[ -n "${apk_path}" ]]; then
    [[ -s "${apk_path}" ]] || fail "APK does not exist: ${apk_path}"
    command -v unzip >/dev/null 2>&1 || fail "unzip is required for APK verification"
    required_entries=(
        'lib/arm64-v8a/libMNN.so'
        'lib/arm64-v8a/libmnn_engine_jni.so'
        'assets/mnn_test_page.html'
    )
    apk_entries="$(unzip -Z1 "${apk_path}")"
    for entry in "${required_entries[@]}"; do
        grep -Fxq "${entry}" <<<"${apk_entries}" || fail "APK is missing ${entry}"
    done
    packaged_dir="$(mktemp -d)"
    cleanup_packaged() {
        rm -rf -- "${packaged_dir}"
    }
    trap cleanup_packaged EXIT
    unzip -p "${apk_path}" 'lib/arm64-v8a/libMNN.so' > "${packaged_dir}/libMNN.so"
    unzip -p "${apk_path}" 'lib/arm64-v8a/libmnn_engine_jni.so' > "${packaged_dir}/libmnn_engine_jni.so"
    verify_elf "${packaged_dir}/libMNN.so"
    verify_elf "${packaged_dir}/libmnn_engine_jni.so"
    "${readelf_bin}" -dW "${packaged_dir}/libmnn_engine_jni.so" | grep -q 'Shared library: \[libMNN.so\]' ||
        fail "packaged libmnn_engine_jni.so does not depend on libMNN.so"
    packaged_symbols="$("${readelf_bin}" -Ws "${packaged_dir}/libmnn_engine_jni.so")"
    for symbol in "${required_symbols[@]}"; do
        grep -q "${symbol}" <<<"${packaged_symbols}" || fail "packaged JNI library is missing ${symbol}"
    done
    build_id() {
        "${readelf_bin}" -n "$1" | sed -n 's/.*Build ID: //p' | head -n 1
    }
    [[ -n "$(build_id "${mnn_library}")" ]] || fail "generated libMNN.so has no GNU Build ID"
    [[ -n "$(build_id "${jni_library}")" ]] || fail "generated libmnn_engine_jni.so has no GNU Build ID"
    [[ "$(build_id "${mnn_library}")" == "$(build_id "${packaged_dir}/libMNN.so")" ]] ||
        fail "packaged libMNN.so Build ID does not match the generated artifact"
    [[ "$(build_id "${jni_library}")" == "$(build_id "${packaged_dir}/libmnn_engine_jni.so")" ]] ||
        fail "packaged libmnn_engine_jni.so Build ID does not match the generated artifact"
    packaged_test_page_hash="$(unzip -p "${apk_path}" 'assets/mnn_test_page.html' | sha256sum | awk '{print $1}')"
    source_test_page_hash="$(sha256sum "${plugin_root}/android/src/main/assets/mnn_test_page.html" | awk '{print $1}')"
    [[ "${packaged_test_page_hash}" == "${source_test_page_hash}" ]] ||
        fail "packaged mnn_test_page.html does not match the plugin asset"
    cleanup_packaged
    trap - EXIT
fi

printf 'MNN native artifacts verified: AArch64 ELF, 16KB LOAD alignment, JNI exports, build metadata%s\n' \
    "${apk_path:+, and APK contents}"
