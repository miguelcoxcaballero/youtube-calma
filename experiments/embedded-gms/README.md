# Embedded microG experiment

This is an isolated engineering probe, **not a working YouTube migration or an update**. It must never be added to an A/B catalog as a functional migration. The production APK, version, signing key and updater remain unchanged.

The goal is one installed APK containing its own authentication and Google service implementations. Bundling an installer for a second APK does not meet that goal. No external microG package is installed by this experiment.

## Why a separate module

The exact YouTube base contains 49,728 DEX classes; the pinned GmsCore APK contains 33,098. Their intersection contains 528 duplicate class names. A direct DEX merge cannot load these implementations reliably. Blindly relocating every Google class also breaks parcelables whose class names are encoded in Bundles, including authentication `TokenData`.

The experiment retains upstream class names in an isolated class loader and runs components inside private processes of the **same installed package and UID**. Most services use `:inhouse_gms`; DroidGuard retains its required separate Binder process as `:inhouse_gms_unstable`. A separate resources context uses public Android APIs. Cross-process Binder parcels use the caller's own classes; Binder interface descriptors must stay unchanged. The standalone probe validates component creation and resource access separately from the opt-in native YouTube candidate.

`upstream.lock.json` pins the official binary, its matching source commit, digest and provenance. The module is never fetched at application runtime. A build may fetch it once after verifying that digest. Keep the original module assets and legal notices intact, along with upstream and third-party attribution. A production module still requires an audit of all included dependency licenses.

## What a passing probe does not establish

- Google sign-in, account switching, token refresh or authenticated playback.
- Routing all existing YouTube GMS calls to the embedded implementation.
- Account, content-provider and service namespace separation from installed microG.
- Notification delivery, background check-in, reboot and Doze behavior.
- Compatibility below the experiment's supported Android API level.
- An upgrade from the current signed YouTube application.

The upstream module targets API 29 while YouTube targets API 35. Foreground services, background launches and receiver registration must be audited and ported. Lowering YouTube's target SDK or suppressing a missing-service warning is not a fix.

Existing credentials belong to the external authenticator and its signing identity. Do not read its private data, bypass Android account protections, or promise automatic session migration. A production embedded authenticator must use its own account type and require a new sign-in.

The pinned original binary contains credential logging. The experimental YouTube backend patch removes sensitive authentication/check-in log calls and redacts `AuthResponse.toString()`; the standalone probe still uses the original binary. Neither test supplies credentials. The optional login-entry profile contacts Google but exports only allowlisted structural diagnostics after opening sign-in. Never capture a real sign-in session in CI logs or artifacts.

## Release acceptance

Before enabling the module in YouTube, test the signed candidate on recent Android versions without either `app.revanced.android.gms` or `com.mgoogle.android.gms` installed. Verify component ownership, fresh login with an authorized test account, subscriptions, playback, token refresh, account removal, notification delivery, and a signed in-place upgrade. Also test coexistence with official Play Services and separately installed microG. Keep credentials and tokens out of CI logs and artifacts.

The existing `scripts/runtime-check.py` installs external microG. Its success cannot establish that YouTube works independently. The component experiment has a separate workflow and report; it must explicitly report authenticated testing as unperformed until real evidence exists.

## Opt-in native YouTube integration

`build-youtube.py` now builds an unpublished native YouTube candidate. See
[BUILD-YOUTUBE.md](BUILD-YOUTUBE.md) for the exact command. Both new patches are
inactive unless `-Dcalma.embeddedGms=true` is set; the ordinary release path and
catalogs remain unchanged. The experimental build keeps the current version code,
so it is not an updater release and must not be announced as a newer version.

`prepare-backend.py` pins and adapts upstream code, preserves Binder protocols and
resource IDs, wraps Android component contexts, removes sensitive auth/check-in
logs, and relocates the seven component names that collide with YouTube. The
isolated module lives inside the host APK and is never separately installed or
downloaded. `prepare-host.py` registers the private services, providers and Android
authenticator under YouTube's real UID. It omits unrelated providers and account
types, uses a separate account type, and supplies host-owned metadata resources.

`youtube-runtime/` keeps YouTube's original Application in its normal processes;
only the two explicit private backend processes use the embedded backend Application. Bundled executable assets
are hash verified and read-only. Module files, databases and preferences have
separate names. This design does not read or migrate another app's credentials.
Fresh authentication is required after a future supported migration.

Do not collapse DroidGuard into the login process. Native Android 15/16 evidence
showed a main-thread deadlock: local Binder callbacks synchronously requested a
Volley result whose delivery required that same blocked main thread. The upstream
manifest puts DroidGuard in a separate process; the host preserves that boundary
under its own private process name, without changing signed response validation
or installing another application. Each backend process has a distinct WebView
data-directory suffix, while immutable payload extraction uses a shared file lock.

The optional same-APK instrumentation and `run-youtube-smoke.py` test the real
YouTube candidate without external microG. Their reports explicitly separate
component availability and a credential-free login page from a completed login,
authenticated playback, token refresh and notification delivery. The latter
still require real device/account evidence before publication.

## Verified results

Commit `db5f29bd146c43b041f8eea316343ffefddfedcd` passed all 24 component checks on Android API 35 and 36 in [run 38248074063](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38248074063). Both devices had official Google Play Services, neither had external microG, and the probe was the only newly installed package. The tests covered isolated code/resources, the actual GServices provider, Android authenticator registration and a real Binder/Parcelable round trip. Credential-free reports are preserved in `evidence/components-api35.json` and `evidence/components-api36.json`.

Commit `e0fe92ea70efc8b238013b7ab2da3ebd45656db5` also passed the standalone login-entry profile on Android API 36 in [run 38254838639](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38254838639). All 24 component checks passed, and the visible Google sign-in page exposed its email/phone field through accessibility. No external microG was installed and no account details were entered. The sanitized report, including its source commit and workflow link, is preserved in [`evidence/login-entry-api36.json`](evidence/login-entry-api36.json).

The native YouTube candidate from commit `842d92cbb8d6727e34aa8ebbb6c61b8632a38ff0`
passed all 39 component/Binder checks and displayed Google's credential-entry
page on Android 14, 15 and 16 in [run 38264289394](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38264289394).
That run reused the exact APK from [build 38262302577](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38262302577),
verified its hash and runtime source fingerprints, and used the corrected
accessibility runner at `1bba38864b26d7d99121ec8ce2cc724aa81a97d6`.
Its SHA-256 is `81c26ab3201608c1f480fa0a6f84cdb54a6d12cc5ee25ac32bf3bcfe96177398`.
All three emulators lacked external microG; Android 14 also had official Google
Play Services disabled. Reports are in `evidence/native-api34.json`,
`native-api35.json` and `native-api36.json`. Android 15's WebView omitted the
input label; its report separately records recognition of the complete usable
identifier-page layout, without pretending a missing label was present.

These Android tests use a disposable certificate and same-APK instrumentation.
The separately built manual candidate retains the permanent Calma signer and
omits the instrumentation registration; its source fingerprints and exact APK
hash are in `evidence/signed-candidate.json`. That exact signed artifact has not
been exercised on a device or published in an update channel.

These results establish native component operation and credential-page entry.
They do not establish completed authentication, subscriptions, playback, token
renewal, notification delivery, reboot behavior or signed in-place upgrades.
The release acceptance requirements above still apply. For the next manual gate,
complete a fresh sign-in from YouTube, open subscriptions, play a video, then
close and reopen the app and repeat. Record only success/failure and relevant
device/version information; do not upload credentials or session tokens.

Any eventual automatic release goes to A. B still requires an explicit request, and A includes newer B releases as specified in the repository's `AGENTS.md`.
