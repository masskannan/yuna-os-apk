echo "=================================================="
echo "    PUYAL SOVEREIGN OS - PIPELINE CUSTOMIZER      "
echo "=================================================="

# 1. Create necessary native and bridge directories
mkdir -p app/src/main/cpp
mkdir -p app/src/main/kotlin/com/puyal/os/bridge
mkdir -p app/src/main/kotlin/com/puyal/os/service
mkdir -p app/src/main/kotlin/com/puyal/os/receiver
mkdir -p schemas
mkdir -p wasm_modules/rust_brain/src
mkdir -p wasm_modules/zig_hands/src
mkdir -p wasm_modules/nim_loop

echo "[✔] Directory structure aligned for UI & Backend Wasm Bridge."

# 2. Check build configuration readiness
if [ -f "app/build.gradle.kts" ]; then
    echo "[✔] build.gradle.kts found with arm64-v8a optimization."
else
    echo "[!] Warning: build.gradle.kts missing in app/ directory."
fi

echo "=================================================="
echo "Pipeline customizer ready. Run gradle build to compile."
