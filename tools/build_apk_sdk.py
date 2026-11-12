#!/usr/bin/env python3
"""Build this dependency-free Java app with official Android SDK tools.

This is an alternative to Gradle, not a stub compiler or APK patcher. Source,
resources, DEX, alignment and signatures are all built/verified from scratch.
Requires JDK 17 and Android SDK platform/build-tools versions in build.gradle.
The generated debug keystore is for local audit builds only; keep it to sign
future audit updates. Never use it as a production/release signing credential.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def run(args):
    print("Running: " + str(args[0]) + " " + str(args[1]), flush=True)
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--keytool", default=shutil.which("keytool"))
    parser.add_argument("--output", default=str(ROOT / "build/sdk-apk"))
    parser.add_argument("--keystore", default=str(ROOT / "build/audit-debug.jks"))
    args = parser.parse_args()
    if not args.sdk or not args.java or not args.keytool:
        parser.error("Supply --sdk, --java and --keytool (JDK 17).")
    sdk = Path(args.sdk).resolve()
    java, keytool = str(Path(args.java).resolve()), str(Path(args.keytool).resolve())
    out, keystore = Path(args.output).resolve(), Path(args.keystore).resolve()
    out.mkdir(parents=True, exist_ok=True)
    config = (ROOT / "build.gradle").read_text()

    def setting(name, numeric=False):
        pattern = r"\b" + name + (r"\s+(\d+)" if numeric else r"\s+'([^']+)'")
        match = re.search(pattern, config)
        if not match:
            raise ValueError("Missing build.gradle setting: " + name)
        return match.group(1)

    namespace = setting("namespace")
    package = setting("applicationId") + setting("applicationIdSuffix")
    version = setting("versionName") + setting("versionNameSuffix")
    code = setting("versionCode", True)
    minimum, target = setting("minSdk", True), setting("targetSdk", True)
    android_jar = sdk / "platforms" / ("android-" + setting("compileSdk", True)) / "android.jar"
    build_tools = sdk / "build-tools" / setting("buildToolsVersion")
    aapt2 = build_tools / "aapt2"
    for path in [android_jar, aapt2, build_tools / "lib/d8.jar", build_tools / "lib/apksigner.jar", build_tools / "zipalign"]:
        if not path.is_file():
            raise FileNotFoundError(path)

    # Build intermediates are dedicated to this script and safe to recreate.
    for folder in ["generated", "classes", "dex"]:
        path = out / folder
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    ET.register_namespace("android", ANDROID_NS)
    tree = ET.parse(ROOT / "AndroidManifest.xml")
    manifest = tree.getroot()
    manifest.set("package", package)
    manifest.set("{" + ANDROID_NS + "}versionCode", code)
    manifest.set("{" + ANDROID_NS + "}versionName", version)
    app = manifest.find("application")
    app.set("{" + ANDROID_NS + "}debuggable", "true")
    # Gradle resolves component names against namespace before applying suffix.
    for component in app.iter():
        for attribute in ["name", "targetActivity"]:
            key = "{" + ANDROID_NS + "}" + attribute
            value = component.get(key)
            if value and value.startswith("."):
                component.set(key, namespace + value)
    tree.write(out / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)

    run([aapt2, "compile", "--dir", ROOT / "res", "-o", out / "resources.zip"])
    resource_apk = out / "resources.apk"
    run([aapt2, "link", "-o", resource_apk, "-I", android_jar,
         "--manifest", out / "AndroidManifest.xml", "--java", out / "generated",
         "--custom-package", namespace, "--min-sdk-version", minimum,
         "--target-sdk-version", target, out / "resources.zip"])
    sources = sorted((ROOT / "src").rglob("*.java")) + sorted((out / "generated").rglob("*.java"))
    # The real android.jar supplies Android APIs; tests/stubs are never compiled.
    run([java, "com.sun.tools.javac.Main", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", android_jar, "-d", out / "classes"] + sources)
    classes_jar = out / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((out / "classes").rglob("*.class")):
            archive.write(path, path.relative_to(out / "classes"))
    run([java, "-cp", build_tools / "lib/d8.jar", "com.android.tools.r8.D8", "--debug",
         "--min-api", minimum, "--lib", android_jar, "--output", out / "dex", classes_jar])
    dex_files = sorted((out / "dex").glob("classes*.dex"))
    if not dex_files:
        raise RuntimeError("DEX compiler produced no output")
    unsigned = out / "unsigned.apk"
    shutil.copyfile(resource_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_STORED) as archive:
        for path in dex_files:
            archive.write(path, path.name)
    aligned = out / "aligned.apk"
    run([build_tools / "zipalign", "-p", "-f", "4", unsigned, aligned])
    if not keystore.exists():
        keystore.parent.mkdir(parents=True, exist_ok=True)
        run([keytool, "-genkeypair", "-keystore", keystore, "-storepass", "android",
             "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000", "-dname", "CN=Falcon Audit Debug,O=Falcon,C=US"])
        keystore.chmod(0o600)
    apk = out / ("FalconQuantum-" + version + ".apk")
    signer = [java, "-jar", build_tools / "lib/apksigner.jar"]
    run(signer + ["sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
                  "--ks-pass", "pass:android", "--key-pass", "pass:android",
                  "--out", apk, aligned])
    verification = subprocess.check_output(signer + ["verify", "--verbose", "--print-certs", str(apk)], text=True)
    (out / "signature-verification.txt").write_text(verification)
    print(verification, flush=True)
    run([build_tools / "zipalign", "-c", "-p", "4", apk])
    metadata = subprocess.check_output([str(aapt2), "dump", "badging", str(apk)], text=True)
    (out / "apk-metadata.txt").write_text(metadata)
    first = metadata.splitlines()[0]
    for expected in ["name='" + package + "'", "versionCode='" + code + "'", "versionName='" + version + "'"]:
        if expected not in first:
            raise RuntimeError("Incorrect APK identity: " + first)
    if "launchable-activity: name='" + namespace + ".MainActivity'" not in metadata:
        raise RuntimeError("Missing launchable application activity")
    report = {"apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "bytes": apk.stat().st_size, "application_id": package, "version": version,
              "version_code": int(code), "min_sdk": int(minimum), "target_sdk": int(target),
              "build_tools": setting("buildToolsVersion"), "source_files": len(sources),
              "live_entries_supported": False, "live_entries_default_enabled": False,
              "live_entries_require_operator_opt_in": False, "device_tested": False,
              "build_method": "Android SDK aapt2 + JDK javac + D8 + zipalign + apksigner"}
    (out / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    (out / "SHA256.txt").write_text(report["sha256"] + "  " + apk.name + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
