#!/usr/bin/env bash
# ==============================================================================
# build-apks.sh — Zero-Config Automated Android APK Build Engine
# 
# Sets up the environment (Java JDK, Android SDK, Licenses, Keystores, Gradle)
# and builds all 3 architecture APKs (arm64-v8a, armeabi-v7a, x86_64) + 1 universal APK
# without requiring manual OS intervention or environment massaging.
# ==============================================================================

set -euo pipefail

# Visual styling
BOLD="\033[1m"
GREEN="\033[0;32m"
BLUE="\033[0;34m"
CYAN="\033[0;36m"
YELLOW="\033[1;33m"
RED="\033[0;31m"
RESET="\033[0m"

info()    { echo -e "${BLUE}[INFO]${RESET} $*"; }
success() { echo -e "${GREEN}[OK]${RESET} ${BOLD}$*${RESET}"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET} $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} ${BOLD}$*${RESET}" >&2; }
die()     { error "$*"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# Default parameters
BUILD_MODE="debug"
CHANNEL="both"
DO_CLEAN=false
OUTPUT_DIR="${SCRIPT_DIR}/dist-apks"

print_usage() {
    cat <<EOF
Usage: ./build-apks.sh [OPTIONS]

Zero-config automated environment setup and multi-architecture APK builder.
Produces 3 standalone architecture APKs (arm64-v8a, armeabi-v7a, x86_64)
+ 1 universal APK with R8 optimization and SHA-256 integrity checksums.

Options:
  --debug          Build debug APKs (default)
  --release        Build minified, signed release APKs (R8 + ProGuard)
  --all            Build both debug and release APK suites
  --channel NAME   Build one channel only: dev (plaintext workspaces), prod (encrypted) or both
                   (default: both)
  --clean          Run Gradle clean before building
  --output-dir DIR Custom directory for output APKs (default: ./dist-apks)
  -h, --help       Show this help message
EOF
}

# Parse command line flags
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)
            BUILD_MODE="debug"
            shift
            ;;
        --release)
            BUILD_MODE="release"
            shift
            ;;
        --all)
            BUILD_MODE="all"
            shift
            ;;
        --clean)
            DO_CLEAN=true
            shift
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --channel)
            CHANNEL="${2:-}"
            case "${CHANNEL}" in
                dev|prod|both) ;;
                *) die "--channel expects dev, prod or both (got '${CHANNEL:-}')" ;;
            esac
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            warn "Unknown option: $1"
            print_usage
            exit 1
            ;;
    esac
done

echo -e "${CYAN}${BOLD}"
echo "=================================================================="
echo "    DivKit SDUI POS Engine — Multi-Architecture APK Builder"
echo "=================================================================="
echo -e "${RESET}"

# The prod channel encrypts `assets/demo/<id>/workspace.bin`, so its Gradle task fails without a
# key. Forward one when the caller supplies it (CI reads it from a repository secret).
GRADLE_ARGS=()
if [[ -n "${APEX_WORKSPACE_KEY:-}" ]]; then
    GRADLE_ARGS+=("-PapexWorkspaceKey=${APEX_WORKSPACE_KEY}")
fi

# ------------------------------------------------------------------------------
# 0. Toolchain Bootstrap Helpers
# ------------------------------------------------------------------------------
# Every step below is *additive*: a usable tool already present in the ambient
# environment (PATH, JAVA_HOME, ANDROID_HOME, ...) always wins and is never
# overwritten. Downloads happen only when a tool is genuinely missing.
TOOLCHAIN_DIR="${APEX_TOOLCHAIN_DIR:-${HOME}/.apex-toolchain}"
SDK_PLATFORM="platforms;android-36.1"
SDK_BUILD_TOOLS="build-tools;36.0.0"

have() { command -v "$1" >/dev/null 2>&1; }

# ensure_on_path <dir> <cmd>... — prepend <dir> only if one of <cmd> is unresolvable.
ensure_on_path() {
    local dir="$1"; shift
    local missing=false c
    for c in "$@"; do have "${c}" || missing=true; done
    if [[ "${missing}" == true && -d "${dir}" ]]; then
        export PATH="${dir}:${PATH}"
        info "Added ${dir} to PATH"
    fi
}

# fetch_to <url> <dest> [label] — curl or wget, atomic via .part
fetch_to() {
    local url="$1" dest="$2" label="${3:-$(basename "$2")}"
    mkdir -p "$(dirname "${dest}")"
    info "  ↓ ${label}"
    if have curl; then
        if curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
                 -A "ApexClient-Build/1.0" -o "${dest}.part" "${url}"; then
            mv -f "${dest}.part" "${dest}"; return 0
        fi
    elif have wget; then
        if wget -q --tries=3 --timeout=30 -O "${dest}.part" "${url}"; then
            mv -f "${dest}.part" "${dest}"; return 0
        fi
    else
        error "  ✗ Neither curl nor wget is available."
        return 1
    fi
    rm -f "${dest}.part"
    return 1
}

# extract_zip <zip> <dir> — unzip, python3, or jar; preserves the exec bit
extract_zip() {
    local zip="$1" dir="$2"
    mkdir -p "${dir}"
    if have unzip; then
        unzip -q -o "${zip}" -d "${dir}"
    elif have python3; then
        # zipfile.extractall drops unix modes, which leaves SDK scripts non-executable.
        python3 - "${zip}" "${dir}" <<'PYEOF'
import os, sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
root = sys.argv[2]
zf.extractall(root)
for info in zf.infolist():
    mode = (info.external_attr >> 16) & 0o777
    if mode and not info.is_dir():
        path = os.path.join(root, info.filename)
        if os.path.exists(path):
            os.chmod(path, mode)
PYEOF
    elif have jar; then
        (cd "${dir}" && jar xf "${zip}")
    else
        error "  ✗ Cannot extract ${zip}: no unzip, python3, or jar available."
        return 1
    fi
    # Belt and braces: SDK launcher scripts must be executable regardless of tool.
    if [[ -d "${dir}/bin" ]]; then
        chmod -R u+x "${dir}/bin" 2>/dev/null || true
    fi
    local d
    for d in "${dir}"/*/bin; do
        [[ -d "${d}" ]] && chmod -R u+x "${d}" 2>/dev/null || true
    done
}

java_major() {   # java_major <java-binary> → major version, 0 if unparseable
    local v
    v="$("$1" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    [[ "${v}" =~ ^[0-9]+$ ]] || { echo 0; return; }
    [[ "${v}" == "1" ]] && v=8   # legacy "1.8.0_x" scheme
    echo "${v}"
}

jdk_usable() {   # jdk_usable <jdk-home> — JDK 17+ with a javac, else false
    local home="$1"
    [[ -n "${home}" && -d "${home}" ]] || return 1
    [[ -x "${home}/bin/java" && -x "${home}/bin/javac" ]] || return 1
    [[ "$(java_major "${home}/bin/java")" -ge 17 ]]
}

sdk_has_platform_tools() { [[ -n "${1:-}" && -d "$1/platform-tools" ]]; }

# pkg_path <sdk-root> <package> — on-disk directory for an sdkmanager package id
# ("platforms;android-36.1" → "<root>/platforms/android-36.1").
pkg_path() {
    local root="$1" pkg="$2"
    echo "${root}/${pkg//;/\/}"
}

# sdk_licenses_present <sdk-root> — licence hash files Gradle checks for
sdk_licenses_present() {
    local root="$1"
    [[ -s "${root}/licenses/android-sdk-license" ]]
}

# write_sdk_licenses <sdk-root> — write the standard Google licence hashes.
# Gradle only checks these files; sdkmanager writes the same content.
write_sdk_licenses() {
    local root="$1" lic="${1}/licenses"
    mkdir -p "${lic}" 2>/dev/null || return 1
    printf '\n24333f8a63cbd82421b10a14942e828b24159494\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63cbd82421b10a14942e828b24159494\n' \
        > "${lic}/android-sdk-license" 2>/dev/null || return 1
    printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' \
        > "${lic}/android-sdk-preview-license" 2>/dev/null || return 1
    return 0
}

# sdkmanager_for <sdk-root> — locate sdkmanager inside <sdk-root> or on PATH
sdkmanager_for() {
    local root="$1" c
    for c in "${root}/cmdline-tools/latest/bin/sdkmanager" \
             "${root}/cmdline-tools/bin/sdkmanager" \
             "${root}/tools/bin/sdkmanager"; do
        if [[ -x "${c}" ]]; then echo "${c}"; return 0; fi
    done
    if have sdkmanager; then command -v sdkmanager; return 0; fi
    return 1
}

# ------------------------------------------------------------------------------
# 1. Java JDK — reuse the environment's JDK, bootstrap Temurin only if absent
# ------------------------------------------------------------------------------
info "Checking Java Development Kit (JDK)..."

JDK_BOOTSTRAPPED=false
JDK_FROM_CACHE=false

# 1a. Honour an explicit, working JAVA_HOME untouched.
if jdk_usable "${JAVA_HOME:-}"; then
    info "Using existing JAVA_HOME: ${JAVA_HOME}"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    warn "JAVA_HOME=${JAVA_HOME} is not a usable JDK 17+; looking for another."
fi

# 1b. Derive JAVA_HOME from a `java` already on PATH (PATH is left alone).
if ! jdk_usable "${JAVA_HOME:-}" && have java; then
    REAL_JAVA="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
    POTENTIAL_HOME="$(dirname "$(dirname "${REAL_JAVA}")")"
    if jdk_usable "${POTENTIAL_HOME}"; then
        export JAVA_HOME="${POTENTIAL_HOME}"
        info "Derived JAVA_HOME from PATH java: ${JAVA_HOME}"
    fi
fi

# 1c. Well-known install locations (Linux & macOS).
if ! jdk_usable "${JAVA_HOME:-}"; then
    for c in "/usr/lib/jvm/java-21-openjdk-amd64" \
             "/usr/lib/jvm/temurin-21-jdk-amd64" \
             "/usr/lib/jvm/java-17-openjdk-amd64" \
             "/usr/lib/jvm/temurin-17-jdk-amd64" \
             "/usr/lib/jvm/default-java" \
             "/opt/java/openjdk" \
             "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" \
             "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"; do
        if jdk_usable "${c}"; then
            export JAVA_HOME="${c}"
            info "Located JDK installation at: ${JAVA_HOME}"
            break
        fi
    done
fi

# 1d. Last resort: download Temurin 21 into the private toolchain dir.
if ! jdk_usable "${JAVA_HOME:-}"; then
    if jdk_usable "${TOOLCHAIN_DIR}/jdk/current"; then
        export JAVA_HOME="${TOOLCHAIN_DIR}/jdk/current"
        JDK_FROM_CACHE=true
        info "Reusing cached JDK: ${JAVA_HOME}"
    else
        case "$(uname -s)" in
            Linux)  JDK_OS="linux" ;;
            Darwin) JDK_OS="mac" ;;
            *) die "Unsupported OS for JDK bootstrap: $(uname -s)" ;;
        esac
        case "$(uname -m)" in
            x86_64|amd64) JDK_ARCH="x64" ;;
            aarch64|arm64) JDK_ARCH="aarch64" ;;
            *) die "Unsupported CPU architecture for JDK bootstrap: $(uname -m)" ;;
        esac
        info "No usable JDK found — downloading Eclipse Temurin 21 (${JDK_OS}/${JDK_ARCH})..."
        JDK_TMP="$(mktemp "${TMPDIR:-/tmp}/jdk-XXXXXX.tar.gz")"
        if ! fetch_to "https://api.adoptium.net/v3/binary/latest/21/ga/${JDK_OS}/${JDK_ARCH}/jdk/hotspot/normal/eclipse" \
                      "${JDK_TMP}" "Temurin JDK 21"; then
            rm -f "${JDK_TMP}"
            die "JDK download failed. Install OpenJDK 17+ or set JAVA_HOME, then re-run."
        fi
        JDK_EXTRACT="${TOOLCHAIN_DIR}/jdk/extract"
        rm -rf "${JDK_EXTRACT}"; mkdir -p "${JDK_EXTRACT}"
        tar -xzf "${JDK_TMP}" -C "${JDK_EXTRACT}" || { rm -f "${JDK_TMP}"; die "JDK archive extraction failed."; }
        rm -f "${JDK_TMP}"
        JDK_ROOT="$(find "${JDK_EXTRACT}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
        [[ -n "${JDK_ROOT}" ]] || die "Unexpected JDK archive layout."
        rm -rf "${TOOLCHAIN_DIR}/jdk/current"
        mv -f "${JDK_ROOT}" "${TOOLCHAIN_DIR}/jdk/current"
        rm -rf "${JDK_EXTRACT}"
        export JAVA_HOME="${TOOLCHAIN_DIR}/jdk/current"
        JDK_BOOTSTRAPPED=true
        success "Installed JDK into ${JAVA_HOME}"
    fi
fi

jdk_usable "${JAVA_HOME:-}" || die "No usable JDK 17+ found. Install one or set JAVA_HOME."
ensure_on_path "${JAVA_HOME}/bin" java javac keytool

JAVA_VERSION_STR="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"
info "Java runtime: ${JAVA_VERSION_STR}"
if [[ "${JDK_BOOTSTRAPPED}" == true ]]; then
    info "JDK source: downloaded (${TOOLCHAIN_DIR}/jdk)"
elif [[ "${JDK_FROM_CACHE}" == true ]]; then
    info "JDK source: cached toolchain (${TOOLCHAIN_DIR}/jdk)"
else
    info "JDK source: existing environment (${JAVA_HOME})"
fi

# ------------------------------------------------------------------------------
# 2. Android SDK — reuse the environment's SDK, bootstrap only what is missing
# ------------------------------------------------------------------------------
info "Checking Android SDK..."

SDK_BOOTSTRAPPED=false
SDK_FROM_CACHE=false

if sdk_has_platform_tools "${ANDROID_HOME:-}"; then
    info "Using existing ANDROID_HOME: ${ANDROID_HOME}"
else
    for p in "${ANDROID_SDK_ROOT:-}" \
             "/opt/android/sdk" "/opt/android-sdk" "/opt/android" \
             "${HOME}/Android/Sdk" "${HOME}/Android/sdk" \
             "${HOME}/Library/Android/sdk" "/usr/lib/android-sdk"; do
        if sdk_has_platform_tools "${p}"; then
            export ANDROID_HOME="${p}"
            info "Located Android SDK at: ${ANDROID_HOME}"
            break
        fi
    done
fi

# 2a. No SDK at all → bootstrap a private one (cmdline-tools + required packages).
if ! sdk_has_platform_tools "${ANDROID_HOME:-}"; then
    SDK_ROOT="${TOOLCHAIN_DIR}/android-sdk"
    if sdk_has_platform_tools "${SDK_ROOT}"; then
        SDK_FROM_CACHE=true
    elif ! sdkmanager_for "${SDK_ROOT}" >/dev/null; then
        case "$(uname -s)" in
            Linux)  CMDLINE_OS="linux" ;;
            Darwin) CMDLINE_OS="mac" ;;
            *) die "Unsupported OS for Android SDK bootstrap: $(uname -s)" ;;
        esac
        info "No Android SDK found — downloading Android command-line tools..."
        CT_ZIP="$(mktemp "${TMPDIR:-/tmp}/cmdline-tools-XXXXXX.zip")"
        if ! fetch_to "https://dl.google.com/android/repository/commandlinetools-${CMDLINE_OS}-13114758_latest.zip" \
                      "${CT_ZIP}" "Android command-line tools"; then
            rm -f "${CT_ZIP}"
            die "Android command-line tools download failed. Set ANDROID_HOME to an existing SDK and re-run."
        fi
        rm -rf "${SDK_ROOT}/cmdline-tools/latest" "${SDK_ROOT}/cmdline-tools/.tmp"
        mkdir -p "${SDK_ROOT}/cmdline-tools"
        if ! extract_zip "${CT_ZIP}" "${SDK_ROOT}/cmdline-tools/.tmp"; then
            rm -f "${CT_ZIP}"; die "Could not extract Android command-line tools."
        fi
        rm -f "${CT_ZIP}"
        mv -f "${SDK_ROOT}/cmdline-tools/.tmp/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
        rm -rf "${SDK_ROOT}/cmdline-tools/.tmp"
    fi
    export ANDROID_HOME="${SDK_ROOT}"
    [[ "${SDK_FROM_CACHE}" == true ]] || SDK_BOOTSTRAPPED=true
fi

export ANDROID_SDK_ROOT="${ANDROID_HOME}"
success "Configured Android SDK: ${ANDROID_HOME}"
if [[ "${SDK_BOOTSTRAPPED}" == true ]]; then
    info "SDK source: downloaded (${TOOLCHAIN_DIR}/android-sdk)"
elif [[ "${SDK_FROM_CACHE}" == true ]]; then
    info "SDK source: cached toolchain (${TOOLCHAIN_DIR}/android-sdk)"
else
    info "SDK source: existing environment (${ANDROID_HOME})"
fi

# 2b. Install only the packages this project needs that are not already present.
SDKMANAGER="$(sdkmanager_for "${ANDROID_HOME}" || true)"
if [[ -z "${SDKMANAGER}" ]]; then
    warn "sdkmanager not found — relying on Gradle's SDK auto-download."
else
    MISSING_PKGS=()
    [[ -d "$(pkg_path "${ANDROID_HOME}" platform-tools)" ]] || MISSING_PKGS+=("platform-tools")
    [[ -d "$(pkg_path "${ANDROID_HOME}" "${SDK_PLATFORM}")" ]] || MISSING_PKGS+=("${SDK_PLATFORM}")
    compgen -G "$(pkg_path "${ANDROID_HOME}" "${SDK_BUILD_TOOLS}")" >/dev/null \
        || MISSING_PKGS+=("${SDK_BUILD_TOOLS}")

    if [[ ${#MISSING_PKGS[@]} -gt 0 ]]; then
        if [[ -w "${ANDROID_HOME}" ]]; then
            info "Installing missing SDK packages: ${MISSING_PKGS[*]}"
            # `yes` closing the pipe early is expected (SIGPIPE); never fatal.
            yes | "${SDKMANAGER}" --sdk_root="${ANDROID_HOME}" --licenses >/dev/null 2>&1 || true
            if ! "${SDKMANAGER}" --sdk_root="${ANDROID_HOME}" --install "${MISSING_PKGS[@]}" >/dev/null 2>&1; then
                warn "sdkmanager could not install: ${MISSING_PKGS[*]} (Gradle may auto-download)."
            else
                success "Installed SDK packages: ${MISSING_PKGS[*]}"
            fi
        else
            warn "Android SDK at ${ANDROID_HOME} is not writable; cannot install ${MISSING_PKGS[*]}."
        fi
    else
        success "All required SDK packages already present."
    fi
fi

# 2b-bis. Licence files. Gradle refuses to auto-download packages unless these
# exist, so write them directly when sdkmanager could not (or is absent).
if [[ -w "${ANDROID_HOME}" ]]; then
    if [[ -n "${SDKMANAGER}" ]]; then
        yes | "${SDKMANAGER}" --sdk_root="${ANDROID_HOME}" --licenses >/dev/null 2>&1 || true
    fi
    if sdk_licenses_present "${ANDROID_HOME}"; then
        info "Android SDK licences accepted."
    elif write_sdk_licenses "${ANDROID_HOME}"; then
        success "Wrote Android SDK licence hashes to ${ANDROID_HOME}/licenses"
    else
        warn "Could not write SDK licences; Gradle may refuse to install packages."
    fi
fi

# 2c. Keep local.properties consistent with the SDK actually in use.
LOCAL_PROPS="${SCRIPT_DIR}/local.properties"
CURRENT_SDK_DIR=""
if [[ -f "${LOCAL_PROPS}" ]]; then
    CURRENT_SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "${LOCAL_PROPS}" | head -n 1)"
fi
if [[ -d "${CURRENT_SDK_DIR:-/nonexistent}" ]]; then
    info "local.properties sdk.dir is valid: ${CURRENT_SDK_DIR}"
else
    if [[ -n "${CURRENT_SDK_DIR}" ]]; then
        warn "local.properties sdk.dir=${CURRENT_SDK_DIR} does not exist; rewriting to ${ANDROID_HOME}"
    else
        info "Writing local.properties sdk.dir=${ANDROID_HOME}"
    fi
    if [[ -f "${LOCAL_PROPS}" ]]; then
        grep -v '^sdk\.dir=' "${LOCAL_PROPS}" > "${LOCAL_PROPS}.tmp" 2>/dev/null || true
        mv -f "${LOCAL_PROPS}.tmp" "${LOCAL_PROPS}"
    fi
    echo "sdk.dir=${ANDROID_HOME}" >> "${LOCAL_PROPS}"
fi

# ------------------------------------------------------------------------------
# 3. Keystore Auto-Generation (Debug & Release Signing)
# ------------------------------------------------------------------------------
info "Checking cryptographic signing keys..."

# keytool ships with the JDK, so resolve it from JAVA_HOME before falling back to PATH.
KEYTOOL_BIN=""
if [[ -x "${JAVA_HOME}/bin/keytool" ]]; then
    KEYTOOL_BIN="${JAVA_HOME}/bin/keytool"
elif have keytool; then
    KEYTOOL_BIN="$(command -v keytool)"
fi

# Debug Keystore
if [[ ! -f "${SCRIPT_DIR}/debug.keystore" ]]; then
    if [[ -f "${SCRIPT_DIR}/debug.keystore.base64" ]]; then
        info "Restoring debug.keystore from base64 snapshot..."
        base64 -d "${SCRIPT_DIR}/debug.keystore.base64" > "${SCRIPT_DIR}/debug.keystore"
    elif [[ -n "${KEYTOOL_BIN}" ]]; then
        info "Generating standard Android debug.keystore via keytool..."
        "${KEYTOOL_BIN}" -genkey -v \
            -keystore "${SCRIPT_DIR}/debug.keystore" \
            -alias "androiddebugkey" \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass "android" \
            -keypass "android" \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    fi
fi
if [[ -f "${SCRIPT_DIR}/debug.keystore" ]]; then
    success "Debug keystore ready: ${SCRIPT_DIR}/debug.keystore"
else
    die "Debug keystore could not be created and keytool is unavailable; install a JDK or provide debug.keystore."
fi

# Release Keystore (for --release or --all)
if [[ "${BUILD_MODE}" == "release" || "${BUILD_MODE}" == "all" ]]; then
    if [[ -z "${KEYSTORE_PATH:-}" && ! -f "${SCRIPT_DIR}/my-upload-key.jks" ]]; then
        if [[ -n "${KEYTOOL_BIN}" ]]; then
            info "Generating local production release signing keystore (my-upload-key.jks)..."
            "${KEYTOOL_BIN}" -genkey -v \
                -keystore "${SCRIPT_DIR}/my-upload-key.jks" \
                -alias "upload" \
                -keyalg RSA \
                -keysize 2048 \
                -validity 10000 \
                -storepass "storepass123" \
                -keypass "storepass123" \
                -dname "CN=SDUI POS Terminal,OU=Production,O=AIStudio,L=MountainView,ST=CA,C=US" >/dev/null 2>&1
            export STORE_PASSWORD="storepass123"
            export KEY_PASSWORD="storepass123"
            success "Generated production upload key: ${SCRIPT_DIR}/my-upload-key.jks"
        fi
    fi
    if [[ -n "${KEYSTORE_PATH:-}" && -z "${STORE_PASSWORD:-}" ]]; then
        export STORE_PASSWORD="android"
        export KEY_PASSWORD="android"
    elif [[ -f "${SCRIPT_DIR}/my-upload-key.jks" && -z "${STORE_PASSWORD:-}" ]]; then
        export STORE_PASSWORD="storepass123"
        export KEY_PASSWORD="storepass123"
    fi
fi

# ------------------------------------------------------------------------------
# 4. Gradle Wrapper & Execution Engine Check
# ------------------------------------------------------------------------------
info "Verifying Gradle build toolchain..."

GRADLE_CMD=""
if [[ -x "${SCRIPT_DIR}/gradlew" ]]; then
    GRADLE_CMD="${SCRIPT_DIR}/gradlew"
elif [[ -f "${SCRIPT_DIR}/gradlew" ]]; then
    chmod +x "${SCRIPT_DIR}/gradlew"
    GRADLE_CMD="${SCRIPT_DIR}/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    info "Bootstrapping Gradle Wrapper using system Gradle..."
    gradle wrapper >/dev/null 2>&1 || true
    if [[ -x "${SCRIPT_DIR}/gradlew" ]]; then
        GRADLE_CMD="${SCRIPT_DIR}/gradlew"
    else
        GRADLE_CMD="gradle"
    fi
else
    die "Neither ./gradlew nor system 'gradle' is available. Please install Gradle 8+."
fi

success "Using Gradle runner: ${GRADLE_CMD}"

# ==============================================================================
# 4b. ML Asset Bootstrap — TFLite / safetensors models for tools://vectorize
# ==============================================================================
# Downloads:
#   • Universal Sentence Encoder Lite (MediaPipe, float32, ~5.5 MB)
#   • MobileNetV4 Conv Small INT8 TFLite (~3.8 MB, from Qualcomm w8a8 zip)
#   • OR model2vec potion-base-2M (model.safetensors + config.json + tokenizer.json, ~2 MB)
#
# Controls:
#   FORCE_ML_ASSETS=true   re-download even if files exist
#   SKIP_ML_ASSETS=true    skip entirely (e.g. offline CI with pre-baked assets)
#   TEXT_EMBEDDER=use-lite (default) | model2vec
# ==============================================================================

ASSETS_DIR="${SCRIPT_DIR}/src/main/assets"
FORCE_ML_ASSETS="${FORCE_ML_ASSETS:-false}"
SKIP_ML_ASSETS="${SKIP_ML_ASSETS:-false}"
TEXT_EMBEDDER="${TEXT_EMBEDDER:-use-lite}"   # use-lite | model2vec

if [[ "${SKIP_ML_ASSETS}" == "true" ]]; then
    info "SKIP_ML_ASSETS=true — skipping ML asset bootstrap."
else
    info "Bootstrapping ML assets into: ${ASSETS_DIR}"
    mkdir -p "${ASSETS_DIR}"

    # ---- helper functions ----------------------------------------------------

    # file_size <path> → bytes (0 if missing)
    file_size() {
        if [[ ! -f "$1" ]]; then echo 0; return; fi
        stat -c%s "$1" 2>/dev/null || stat -f%z "$1" 2>/dev/null || echo 0
    }

    # need_download <path> <min_bytes>
    need_download() {
        local path="$1" min="$2"
        [[ "${FORCE_ML_ASSETS}" == "true" ]] && return 0
        [[ ! -f "${path}" ]] && return 0
        local sz; sz=$(file_size "${path}")
        [[ "${sz}" -lt "${min}" ]] && return 0
        return 1
    }

    # is_tflite <path> — sanity check: TFL3 magic at offset 4
    is_tflite() {
        local p="$1"
        [[ ! -f "${p}" ]] && return 1
        # Read bytes 4-7; TFLite flatbuffer identifier is "TFL3"
        local magic
        magic=$(dd if="${p}" bs=1 skip=4 count=4 2>/dev/null | od -An -c | tr -d ' \n')
        [[ "${magic}" == "TFL3" ]]
    }

    # fetch <url> <dest> <label> — thin wrapper over fetch_to (curl or wget).
    fetch() {
        local url="$1" dest="$2" label="$3"
        if ! fetch_to "${url}" "${dest}" "${label}"; then
            error "  ✗ Download failed: ${label} (${url})"
            return 1
        fi
        success "  ✓ ${label} ($(numfmt --to=iec-i --suffix=B "$(file_size "${dest}")" 2>/dev/null || echo "? bytes"))"
        return 0
    }

    ASSET_FAILURES=0

    # ---- 1. MobileNetV4 Conv Small INT8 TFLite (≈ 3.8 MB) -------------------
    MV4_TFLITE="${ASSETS_DIR}/mobilenetv4_small_int8.tflite"
    MV4_MIN=$((3 * 1024 * 1024))   # 3 MB floor
    MV4_ZIP_URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobilenet_v4/releases/v0.64.0/mobilenet_v4-tflite-w8a8.zip"

    if need_download "${MV4_TFLITE}" "${MV4_MIN}" || ! is_tflite "${MV4_TFLITE}"; then
        TMP_ZIP="$(mktemp "${TMPDIR:-/tmp}/mv4-XXXXXX.zip")"
        if fetch "${MV4_ZIP_URL}" "${TMP_ZIP}" "MobileNetV4 w8a8 zip"; then
            TMP_EXTRACT="$(mktemp -d "${TMPDIR:-/tmp}/mv4-XXXXXX")"
            if ! extract_zip "${TMP_ZIP}" "${TMP_EXTRACT}"; then
                ASSET_FAILURES=$((ASSET_FAILURES + 1))
            fi

            # Locate the .tflite inside the extracted tree
            FOUND_TFLITE="$(find "${TMP_EXTRACT}" -type f -name '*.tflite' | head -n 1 || true)"
            if [[ -n "${FOUND_TFLITE}" && -f "${FOUND_TFLITE}" ]]; then
                cp -f "${FOUND_TFLITE}" "${MV4_TFLITE}"
                if is_tflite "${MV4_TFLITE}"; then
                    success "  ✓ MobileNetV4 TFLite installed ($(numfmt --to=iec-i --suffix=B "$(file_size "${MV4_TFLITE}")"))"
                else
                    error "  ✗ Extracted file failed TFLite magic-byte check."
                    rm -f "${MV4_TFLITE}"
                    ASSET_FAILURES=$((ASSET_FAILURES + 1))
                fi
            else
                error "  ✗ No .tflite found inside MobileNetV4 zip."
                ASSET_FAILURES=$((ASSET_FAILURES + 1))
            fi
            rm -rf "${TMP_EXTRACT}"
        else
            ASSET_FAILURES=$((ASSET_FAILURES + 1))
        fi
        rm -f "${TMP_ZIP}"
    else
        success "  ✓ MobileNetV4 TFLite already present ($(numfmt --to=iec-i --suffix=B "$(file_size "${MV4_TFLITE}")"))"
    fi

    # ---- 2. Text embedder (choose USE-Lite or model2vec) --------------------

    case "${TEXT_EMBEDDER}" in
        use-lite)
            USE_LITE="${ASSETS_DIR}/universal_sentence_encoder_lite.tflite"
            USE_LITE_MIN=$((5 * 1024 * 1024))   # 5 MB floor (actual ~5.5 MB)
            USE_LITE_URL="https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

            if need_download "${USE_LITE}" "${USE_LITE_MIN}" || ! is_tflite "${USE_LITE}"; then
                if fetch "${USE_LITE_URL}" "${USE_LITE}" "Universal Sentence Encoder Lite (MediaPipe)"; then
                    if ! is_tflite "${USE_LITE}"; then
                        error "  ✗ USE-Lite download failed TFLite magic-byte check."
                        rm -f "${USE_LITE}"
                        ASSET_FAILURES=$((ASSET_FAILURES + 1))
                    fi
                else
                    ASSET_FAILURES=$((ASSET_FAILURES + 1))
                fi
            else
                success "  ✓ USE-Lite already present ($(numfmt --to=iec-i --suffix=B "$(file_size "${USE_LITE}")"))"
            fi
            ;;

        model2vec)
            M2V_DIR="${ASSETS_DIR}/potion-base-2M"
            mkdir -p "${M2V_DIR}"
            M2V_BASE="https://huggingface.co/minishlab/potion-base-2M/resolve/main"
            M2V_FILES=("model.safetensors" "config.json" "tokenizer.json")
            M2V_MIN_EACH=1024   # each file must be ≥ 1 KB (rejects HTML error pages)

            M2V_OK=true
            for f in "${M2V_FILES[@]}"; do
                dest="${M2V_DIR}/${f}"
                if need_download "${dest}" "${M2V_MIN_EACH}"; then
                    if ! fetch "${M2V_BASE}/${f}" "${dest}" "model2vec / ${f}"; then
                        M2V_OK=false
                        ASSET_FAILURES=$((ASSET_FAILURES + 1))
                    fi
                else
                    success "  ✓ model2vec / ${f} already present"
                fi
            done

            if [[ "${M2V_OK}" == "true" ]]; then
                success "  ✓ model2vec potion-base-2M installed ($(numfmt --to=iec-i --suffix=B "$(du -sb "${M2V_DIR}" 2>/dev/null | awk '{print $1}')" 2>/dev/null || echo "~2 MB"))"
            fi
            ;;

        *)
            warn "  Unknown TEXT_EMBEDDER='${TEXT_EMBEDDER}' — defaulting to use-lite."
            # Re-run this block with use-lite
            TEXT_EMBEDDER=use-lite
            # (fall through is not possible in case; recurse once)
            USE_LITE="${ASSETS_DIR}/universal_sentence_encoder_lite.tflite"
            if need_download "${USE_LITE}" $((5 * 1024 * 1024)); then
                fetch "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite" \
                      "${USE_LITE}" "Universal Sentence Encoder Lite" || ASSET_FAILURES=$((ASSET_FAILURES + 1))
            fi
            ;;
    esac

    # ---- 3. Summary ---------------------------------------------------------
    if [[ "${ASSET_FAILURES}" -gt 0 ]]; then
        warn "  ${ASSET_FAILURES} ML asset download(s) failed. The APK will still build;"
        warn "  tools://vectorize will return UNSUPPORTED for the missing modality at runtime."
        warn "  Re-run with FORCE_ML_ASSETS=true once network is available."
    else
        success "  All ML assets ready in ${ASSETS_DIR}"
    fi
fi

# ------------------------------------------------------------------------------
# 5. Build Execution
# ------------------------------------------------------------------------------
TASKS=()

if [[ "${DO_CLEAN}" == true ]]; then
    TASKS+=("clean")
fi

variant_task() {
    # variant_task <debug|release> — the channel-qualified assemble task, or the
    # build-type aggregate when both channels are wanted.
    local build_type="$1" capital
    capital="$(tr '[:lower:]' '[:upper:]' <<< "${build_type:0:1}")${build_type:1}"
    case "${CHANNEL}" in
        dev)  echo "assembleDev${capital}" ;;
        prod) echo "assembleProd${capital}" ;;
        *)    echo "assemble${capital}" ;;
    esac
}

case "${BUILD_MODE}" in
    debug)
        TASKS+=("$(variant_task debug)")
        ;;
    release)
        TASKS+=("$(variant_task release)")
        ;;
    all)
        TASKS+=("$(variant_task debug)" "$(variant_task release)")
        ;;
esac

echo ""
info "Executing Gradle build tasks: ${TASKS[*]} ..."
echo ""

"${GRADLE_CMD}" "${TASKS[@]}" "${GRADLE_ARGS[@]}"

echo ""
success "Gradle compilation and packaging completed successfully!"

# ------------------------------------------------------------------------------
# 6. Artifact Harvesting, Checksumming & Reporting
# ------------------------------------------------------------------------------
mkdir -p "${OUTPUT_DIR}"

# Variants are channel-qualified (`dev`/`prod`), so the APKs land in
# build/outputs/apk/<channel>/<buildType>. The flat paths are kept for older trees.
SEARCH_DIRS=()
SEARCH_CHANNELS=()
case "${CHANNEL}" in
    both) SEARCH_CHANNELS=("dev" "prod") ;;
    *)    SEARCH_CHANNELS=("${CHANNEL}") ;;
esac
for channel in "${SEARCH_CHANNELS[@]}"; do
    if [[ "${BUILD_MODE}" == "debug" || "${BUILD_MODE}" == "all" ]]; then
        SEARCH_DIRS+=("${SCRIPT_DIR}/build/outputs/apk/${channel}/debug")
    fi
    if [[ "${BUILD_MODE}" == "release" || "${BUILD_MODE}" == "all" ]]; then
        SEARCH_DIRS+=("${SCRIPT_DIR}/build/outputs/apk/${channel}/release")
    fi
done
# Flat paths predate the channel flavours; kept so an older tree still harvests.
if [[ "${BUILD_MODE}" == "debug" || "${BUILD_MODE}" == "all" ]]; then
    SEARCH_DIRS+=("${SCRIPT_DIR}/build/outputs/apk/debug")
fi
if [[ "${BUILD_MODE}" == "release" || "${BUILD_MODE}" == "all" ]]; then
    SEARCH_DIRS+=("${SCRIPT_DIR}/build/outputs/apk/release")
fi

COLLECTED_APKS=()
for d in "${SEARCH_DIRS[@]}"; do
    if [[ -d "${d}" ]]; then
        while IFS= read -r -d '' apk_file; do
            filename="$(basename "${apk_file}")"
            cp -f "${apk_file}" "${OUTPUT_DIR}/${filename}"
            COLLECTED_APKS+=("${OUTPUT_DIR}/${filename}")
        done < <(find "${d}" -maxdepth 1 -type f -name "*.apk" -print0)
    fi
done

if [[ ${#COLLECTED_APKS[@]} -eq 0 ]]; then
    warn "No APK files found in standard build output directories."
    exit 0
fi

# Generate SHA-256 Checksums manifest
CHECKSUM_FILE="${OUTPUT_DIR}/checksums.sha256"
rm -f "${CHECKSUM_FILE}"

echo -e "\n${CYAN}${BOLD}=================================================================="
echo "                   BUILT ARTIFACTS SUMMARY"
echo "==================================================================${RESET}"
printf "%-32s | %-10s | %-12s | %s\n" "APK Name" "Variant" "Size" "SHA-256 (First 12 chars)"
echo "---------------------------------------------------------------------------------------"

for apk in "${COLLECTED_APKS[@]}"; do
    fname="$(basename "${apk}")"
    
    # Extract variant
    variant="Unknown"
    if [[ "${fname}" == *"-debug"* ]]; then
        variant="Debug"
    elif [[ "${fname}" == *"-release"* ]]; then
        variant="Release"
    fi

    # Extract human-readable size
    if command -v numfmt >/dev/null 2>&1; then
        bytes=$(stat -c%s "${apk}" 2>/dev/null || stat -f%z "${apk}" 2>/dev/null || echo 0)
        hsize=$(numfmt --to=iec-i --suffix=B "${bytes}" 2>/dev/null || ls -lh "${apk}" | awk '{print $5}')
    else
        hsize=$(ls -lh "${apk}" | awk '{print $5}')
    fi

    # Compute SHA-256
    if command -v sha256sum >/dev/null 2>&1; then
        csum=$(sha256sum "${apk}" | awk '{print $1}')
    elif command -v shasum >/dev/null 2>&1; then
        csum=$(shasum -a 256 "${apk}" | awk '{print $1}')
    else
        csum="n/a"
    fi

    echo "${csum}  ${fname}" >> "${CHECKSUM_FILE}"
    printf "%-32s | %-10s | %-12s | %s\n" "${fname}" "${variant}" "${hsize}" "${csum:0:12}..."
done

echo "---------------------------------------------------------------------------------------"
echo -e "\n${GREEN}${BOLD}✓ All APKs successfully built and saved to:${RESET} ${OUTPUT_DIR}/"
echo -e "${GREEN}${BOLD}✓ SHA-256 checksum manifest generated at:${RESET} ${CHECKSUM_FILE}\n"
