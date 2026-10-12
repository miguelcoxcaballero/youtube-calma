# Embedded microG component probe

This is a disposable Android harness, not YouTube Calma and not a user release.
It has its own `es.inhouse.gmsprobe` package and debug signing key. The upstream
GmsCore APK is an immutable asset loaded within the same application; the probe
never calls a package installer or downloads additional software at runtime.

The emulator checks run on clean Android 15/16 devices with official Google Play
Services, and explicitly require both ReVanced and Vanced microG to be absent.
They test isolated class/resources loading, registered providers and account
authenticator ownership, a real Gservices database round trip, and real upstream
auth Binder interfaces. Only the probe APK may be installed by the test.

**A passing probe does not verify Google sign-in, YouTube playback, push messages,
caller identity, complete login UI, or migration of an existing microG account.**
Those require further integration and testing before any release can be offered.
The upstream authority and `app.revanced` account type are used only on this clean
test device; production coexistence requires a separate namespace and migration.

Build with `python3 experiments/embedded-gms/build-harness.py`. A checked-in
`runtime/{host,bridge,stubs}` is required. `stubs` are compile-time definitions
only and are excluded from both executable DEX files. Use `--runtime-source`
to point at another audited copy. `run-harness.py --api 35` (or `36`) requires
Android SDK command-line tools and KVM. The workflow runs only on explicit
dispatch or pushes to the dedicated experimental branch.

The optional `--login-entry` build/run flag adds the original login activity and
settings provider. It opens the local sign-in introduction, presses Sign in, and
checks whether Google's email entry page appears. It never enters an account name
or password. Device check-in may contact Google. Use the workflow's `login_entry`
dispatch input to request this extra profile. Core component logs are saved before
the online entry starts, then cleared. Online diagnostics deliberately exclude raw
logcat, page URLs, remote response text and accessibility XML; only structural
exception stacks and boolean observations are retained. Seeing a login
page is still not proof that authentication or YouTube account access works.

Dependencies are SHA-256 locked in the builder. The original GmsCore asset,
including its resource and dependency notices, remains intact. Bundled licensing
information is under `assets/licenses`; see that provenance before redistribution.
