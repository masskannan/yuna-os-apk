import os
import re
import sys

errors = []

def read(path):
    if not os.path.exists(path):
        errors.append(f"Missing file: {path}")
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

strings_xml = read("app/src/main/res/values/strings.xml")
defined_strings = set(re.findall(r'<string\s+name="([^"]+)"', strings_xml))

xml_files = ["app/src/main/AndroidManifest.xml"]
xml_dir = "app/src/main/res/xml"
if os.path.isdir(xml_dir):
    for fn in os.listdir(xml_dir):
        if fn.endswith(".xml"):
            xml_files.append(os.path.join(xml_dir, fn))

for xf in xml_files:
    content = read(xf)
    for sref in re.findall(r"@string/([A-Za-z0-9_]+)", content):
        if sref not in defined_strings:
            errors.append(f"{xf} references @string/{sref} which is missing in strings.xml")
    for xref in re.findall(r"@xml/([A-Za-z0-9_]+)", content):
        if not os.path.exists(f"app/src/main/res/xml/{xref}.xml"):
            errors.append(f"{xf} references @xml/{xref} which is missing in res/xml/")

gradle = read("app/build.gradle.kts")
for dep in ["junit:junit", "androidx.webkit:webkit", "androidx.work:work-runtime-ktx", "androidx.biometric:biometric", "lint {"]:
    if dep not in gradle:
        errors.append(f"app/build.gradle.kts missing required config/dependency: {dep}")

pg = read("app/proguard-rules.pro")
for rule in ["com.puyal.os.nativebridge.**", "YunaBridge", "com.puyal.os.service.**"]:
    if rule not in pg:
        errors.append(f"app/proguard-rules.pro missing keep rule: {rule}")

rust = read("wasm_modules/rust_brain/src/lib.rs")
if "#![no_std]" in rust or "#[panic_handler]" in rust:
    errors.append("wasm_modules/rust_brain/src/lib.rs still contains #![no_std] or #[panic_handler]")


pcs = read("app/src/main/kotlin/com/puyal/os/service/PuyalCoreService.kt")
if "fgReceiver" in pcs and "val fgReceiver" not in pcs:
    errors.append("PuyalCoreService.kt references fgReceiver without declaring val fgReceiver")
if pcs.count("var bubbleViewRef") > 1:
    errors.append("PuyalCoreService.kt has duplicate bubbleViewRef declaration")
if not re.search(r"private\s+fun\s+registerScreenReceiver\s*\(\s*\)\s*\{", pcs):
    errors.append("PuyalCoreService.kt has broken registerScreenReceiver() declaration")

if errors:
    print("PRE-PUSH AUDIT FAILED:")
    for e in errors:
        print("  [X]", e)
    sys.exit(1)

print("[✔] PRE-PUSH AUDIT PASSED: All XML resources, Gradle deps, ProGuard rules, and Rust std checks OK.")
