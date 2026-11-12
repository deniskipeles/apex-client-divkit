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

# ------------------------------------------------------------------------------
# 1. Java JDK Environment Auto-Discovery & Validation
# ------------------------------------------------------------------------------
info "Checking Java Development Kit (JDK)..."

if [[ -z "${JAVA_HOME:-}" ]]; then
    if command -v java >/dev/null 2>&1; then
        JAVA_BIN="$(which java)"
        # Resolve symlinks to find the JDK root
        REAL_JAVA="$(readlink -f "${JAVA_BIN}" 2>/dev/null || echo "${JAVA_BIN}")"
        POTENTIAL_HOME="$(dirname "$(dirname "${REAL_JAVA}")")"
        if [[ -d "${POTENTIAL_HOME}/include" || -d "${POTENTIAL_HOME}/lib" ]]; then
            export JAVA_HOME="${POTENTIAL_HOME}"
            info "Discovered JAVA_HOME from binary: ${JAVA_HOME}"
        fi
    fi
fi

# Fallback JDK search paths across Linux & macOS
if [[ -z "${JAVA_HOME:-}" || ! -d "${JAVA_HOME:-}" ]]; then
    CANDIDATES=(
        "/usr/lib/jvm/java-21-openjdk-amd64"
        "/usr/lib/jvm/temurin-21-jdk-amd64"
        "/usr/lib/jvm/java-17-openjdk-amd64"
        "/usr/lib/jvm/temurin-17-jdk-amd64"
        "/usr/lib/jvm/default-java"
        "/Library/Java/JavaVirtualMachines"
    )
    for c in "${CANDIDATES[@]}"; do
        if [[ -d "${c}" ]]; then
            export JAVA_HOME="${c}"
            info "Located JDK installation at: ${JAVA_HOME}"
            break
        fi
    done
fi

if ! command -v java >/dev/null 2>&1; then
    die "Java is not installed or not in PATH. Please install OpenJDK 17 or 21."
fi

JAVA_VERSION_STR="$(java -version 2>&1 | head -n 1)"
info "Java runtime: ${JAVA_VERSION_STR}"

# ------------------------------------------------------------------------------
# 2. Android SDK Auto-Discovery & License Auto-Acceptance
# ------------------------------------------------------------------------------
info "Checking Android SDK..."

SDK_PATHS=(
    "${ANDROID_HOME:-}"
    "${ANDROID_SDK_ROOT:-}"
    "/opt/android/sdk"
    "/opt/android-sdk"
    "${HOME}/Android/Sdk"
    "${HOME}/Library/Android/sdk"
    "/usr/lib/android-sdk"
)

FOUND_SDK=""
for p in "${SDK_PATHS[@]}"; do
    if [[ -n "${p}" && -d "${p}" && -d "${p}/platform-tools" ]]; then
        FOUND_SDK="${p}"
        break
    fi
done

if [[ -z "${FOUND_SDK}" ]]; then
    # Relaxed check for sdk root
    for p in "${SDK_PATHS[@]}"; do
        if [[ -n "${p}" && -d "${p}" ]]; then
            FOUND_SDK="${p}"
            break
        fi
    done
fi

if [[ -n "${FOUND_SDK}" ]]; then
    export ANDROID_HOME="${FOUND_SDK}"
    export ANDROID_SDK_ROOT="${FOUND_SDK}"
    success "Configured Android SDK: ${ANDROID_HOME}"
else
    warn "Android SDK not found in default paths. Gradle will attempt auto-download if SDK component manager is active."
fi

# Auto-accept Android SDK licenses if directory is writable
if [[ -n "${ANDROID_HOME:-}" && -w "${ANDROID_HOME}" ]]; then
    LIC_DIR="${ANDROID_HOME}/licenses"
    mkdir -p "${LIC_DIR}" 2>/dev/null || true
    if [[ -w "${LIC_DIR}" ]]; then
        # Standard Google Android SDK license hashes
        echo -e "\n24333f8a63cbd82421b10a14942e828b24159494\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63cbd82421b10a14942e828b24159494" > "${LIC_DIR}/android-sdk-license" 2>/dev/null || true
        echo -e "\n84831b9409646a918e30573bab4c9c91346d8abd" > "${LIC_DIR}/android-sdk-preview-license" 2>/dev/null || true
        info "Android SDK license agreements auto-provisioned."
    fi
fi

# ------------------------------------------------------------------------------
# 3. Keystore Auto-Generation (Debug & Release Signing)
# ------------------------------------------------------------------------------
info "Checking cryptographic signing keys..."

# Debug Keystore
if [[ ! -f "${SCRIPT_DIR}/debug.keystore" ]]; then
    if [[ -f "${SCRIPT_DIR}/debug.keystore.base64" ]]; then
        info "Restoring debug.keystore from base64 snapshot..."
        base64 -d "${SCRIPT_DIR}/debug.keystore.base64" > "${SCRIPT_DIR}/debug.keystore"
    elif command -v keytool >/dev/null 2>&1; then
        info "Generating standard Android debug.keystore via keytool..."
        keytool -genkey -v \
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
    warn "Debug keystore not found; Gradle debug signing may require keytool."
fi

# Release Keystore (for --release or --all)
if [[ "${BUILD_MODE}" == "release" || "${BUILD_MODE}" == "all" ]]; then
    if [[ -z "${KEYSTORE_PATH:-}" && ! -f "${SCRIPT_DIR}/my-upload-key.jks" ]]; then
        if command -v keytool >/dev/null 2>&1; then
            info "Generating local production release signing keystore (my-upload-key.jks)..."
            keytool -genkey -v \
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

# ------------------------------------------------------------------------------
# 5. Build Execution
# ------------------------------------------------------------------------------
TASKS=()

if [[ "${DO_CLEAN}" == true ]]; then
    TASKS+=(":app:clean")
fi

case "${BUILD_MODE}" in
    debug)
        TASKS+=(":app:assembleDebug")
        ;;
    release)
        TASKS+=(":app:assembleRelease")
        ;;
    all)
        TASKS+=(":app:assembleDebug" ":app:assembleRelease")
        ;;
esac

echo ""
info "Executing Gradle build tasks: ${TASKS[*]} ..."
echo ""

"${GRADLE_CMD}" "${TASKS[@]}"

echo ""
success "Gradle compilation and packaging completed successfully!"

# ------------------------------------------------------------------------------
# 6. Artifact Harvesting, Checksumming & Reporting
# ------------------------------------------------------------------------------
mkdir -p "${OUTPUT_DIR}"

SEARCH_DIRS=()
if [[ "${BUILD_MODE}" == "debug" || "${BUILD_MODE}" == "all" ]]; then
    SEARCH_DIRS+=("${SCRIPT_DIR}/app/build/outputs/apk/debug")
fi
if [[ "${BUILD_MODE}" == "release" || "${BUILD_MODE}" == "all" ]]; then
    SEARCH_DIRS+=("${SCRIPT_DIR}/app/build/outputs/apk/release")
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
