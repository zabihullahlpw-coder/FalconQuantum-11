# APK build and update

The direct SDK build uses JDK 17, Android SDK platform 34 and Build Tools
34.0.0. It compiles all production Java and resources, generates DEX, aligns
and signs the APK, and verifies its signature and application identity.
Gradle is not required for this route. See the delivered build record for
actual compiler output, test output, APK checksum and certificate checks.

## Build with the retained signing key

```bash
python3 tools/build_apk_sdk.py --sdk /absolute/path/to/Android/Sdk --keystore /absolute/path/to/audit-debug.jks
```

If Java and keytool are not on PATH, supply `--java` and `--keytool` paths.
If native tools cannot find their shared libraries, include the JDK's `lib`
folder and SDK `build-tools/34.0.0/lib64` in `LD_LIBRARY_PATH`.

Output: `build/sdk-apk/FalconQuantum-10.4.2-debug.apk`.

The script never compiles `tests/stubs` into the application. It uses the
real Android SDK for the production compilation. Build identity comes from
`build.gradle`: namespace `com.falcon.quantum`, debug application ID
`com.falcon.quantum.audit`, version code `10402`, version `10.4.2-debug`,
minimum SDK 24 and target SDK 34.

## Install the update

The delivered 10.4.2 APK is built with the retained signing key from the
10.4.1 audit APK. Install over that audit app; do not uninstall it first.
The same package and certificate allow an in-place update preserving its
application data. Actual phone installation and data retention still need
verification on the device. The original 10.4.0 app uses another package
and is not replaced by this update.

Open ACCOUNT in LIVE, save the appropriate credentials if not already saved,
and check **Enable LIVE order sending (real funds)**. Checking enables the
permission; unchecking blocks new entries. The default is off. Auto-open and
unattended background orders are separate switches and are not enabled by
this checkbox. Orders already submitted can still fill after switching off;
existing positions continue to need protection or closing.

## Alternative Gradle build

With Gradle 8.7 installed, run `bash tools/build_android.sh`. A manual GitHub
Actions workflow is also included at `.github/workflows/android-apk.yml`.
It has not been run as part of this update. A fresh CI runner creates a new
debug key unless explicitly configured otherwise, so a CI APK may not update
the audit app signed with the retained key.

## Validation boundary

A successful compilation/signature check does not establish profitability,
Binance acceptance or successful operation on a phone. The local LIVE tests
intercept HTTPS entirely and cannot place Binance orders. See `VALIDATION.md`
for remaining device, PAPER and DEMO checks. LIVE permission is now controlled
by the operator; no local test or backtest automatically enables it.

## Primary tool references

- https://developer.android.com/tools/aapt2
- https://developer.android.com/tools/d8
- https://developer.android.com/tools/apksigner
- https://developer.android.com/build/building-cmdline
