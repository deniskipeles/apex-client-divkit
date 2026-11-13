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

ASSETS_DIR="${SCRIPT_DIR}/app/src/main/assets"
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

    # fetch <url> <dest> <label> [extra curl args…]
    fetch() {
        local url="$1" dest="$2" label="$3"; shift 3
        info "  ↓ ${label}"
        if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 \
                  -A "ApexClient-Build/1.0" \
                  "$@" -o "${dest}.part" "${url}"; then
            rm -f "${dest}.part"
            error "  ✗ Download failed: ${label} (${url})"
            return 1
        fi
        mv -f "${dest}.part" "${dest}"
        success "  ✓ ${label} ($(numfmt --to=iec-i --suffix=B "$(file_size "${dest}")" 2>/dev/null || echo "? bytes"))"
        return 0
    }

    ASSET_FAILURES=0

    # ---- 1. MobileNetV4 Conv Small INT8 TFLite (≈ 3.8 MB) -------------------
    MV4_TFLITE="${ASSETS_DIR}/mobilenetv4_small_int8.tflite"
    MV4_MIN=$((3 * 1024 * 1024))   # 3 MB floor
    MV4_ZIP_URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobilenet_v4/releases/v0.64.0/mobilenet_v4-tflite-w8a8.zip"

    if need_download "${MV4_TFLITE}" "${MV4_MIN}" || ! is_tflite "${MV4_TFLITE}"; then
        if ! command -v unzip >/dev/null 2>&1; then
            warn "  unzip not found — trying python3 zipfile fallback."
        fi

        TMP_ZIP="$(mktemp "${TMPDIR:-/tmp}/mv4-XXXXXX.zip")"
        if fetch "${MV4_ZIP_URL}" "${TMP_ZIP}" "MobileNetV4 w8a8 zip"; then
            TMP_EXTRACT="$(mktemp -d "${TMPDIR:-/tmp}/mv4-XXXXXX")"
            if command -v unzip >/dev/null 2>&1; then
                unzip -q -o "${TMP_ZIP}" -d "${TMP_EXTRACT}"
            elif command -v python3 >/dev/null 2>&1; then
                python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
                    "${TMP_ZIP}" "${TMP_EXTRACT}"
            else
                error "  ✗ Cannot extract zip: neither unzip nor python3 available."
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
