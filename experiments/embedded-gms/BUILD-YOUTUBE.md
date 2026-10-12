# Experimental YouTube integration build

`build-youtube.py` produces a current-version, unpublished candidate in
`build/embedded-youtube`. It keeps the original package, permanent Calma signer,
native YouTube application and shared updater. It does not bump `CalmaBuild`,
write a channel catalog, publish a release, or run the production `build.py`.
Its current version code is intentional for local experiments; it is not a
distributable update. Successful compilation does not establish Google sign-in,
signed-in playback, notification delivery or upgrade compatibility.

```sh
python3 experiments/embedded-gms/build-youtube.py \
  --gms-apk /path/to/pinned-original-gmscore.apk \
  --morphe /path/to/morphe-desktop-1.18.1-all.jar \
  --keystore /path/to/existing-calma-signing.p12 \
  --instrumentation
```

Set `CALMA_KEYSTORE_PASSWORD` in the environment when the existing keystore uses
a password other than the local build default. The ordinary signing path never
creates a key. CI may use `--debug-signing --instrumentation` instead of
`--keystore`; that explicit test-only path generates a disposable certificate
under the experiment output and cannot upgrade an installed Calma release.
Java 21 or newer is required by the pinned Morphe patcher. Android tools default
to the repository's existing `tools/android-15`, API 35
stubs and ECJ; pass `--build-tools`, `--android-jar` and `--ecj` to override them.
The original YouTube APK, GmsCore input and Morphe JAR must match their checked-in
SHA-256 pins before work begins.

`prepare-backend.py` adapts the module and emits its exact transformation report.
For iteration, pass `--backend-apk /path/to/adapted-gmscore.apk --backend-report
/path/to/adapted-gmscore.report.json`. The wrapper verifies both the binary hash
and current backend patch/preparer hashes; stale adapted modules are rejected.
`prepare-host.py` recreates the necessary Android-parsed descriptors in host
resources. `--instrumentation` registers the explicit smoke-test entry point.

The isolated bridge and host runtime compile separately. `EmbeddedGmsBuild` is
generated from the final backend/bridge hashes and exact component allowlist.
AndroidX compile stubs never enter the APK. The host DEX is checked for collisions
with the pinned YouTube classes and bypasses Calma's updater runtime shading.
The ordinary Calma extension and all existing patches are included, while the
embedded patches only execute with `-Dcalma.embeddedGms=true`.

`--prepare-only` verifies and compiles the patch inputs without patching or
signing the whole APK. A complete build verifies the signed package, code,
version metadata, permanent signing certificate, embedded asset hashes and ZIP
alignment. `build-report.json` records exact build hashes and leaves all runtime
claims false until separate tests supply evidence. The wrapper checks that
release catalogs, release identity and shared updater files stayed unchanged.

The optional instrumentation and module payload remain inside the same APK.
Neither the wrapper nor the runtime installs an external microG package. The
runtime does not download executable code. External microG accounts and tokens
are not copied into the new authenticator.
