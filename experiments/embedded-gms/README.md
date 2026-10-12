# Embedded microG experiment

This is an isolated engineering probe, **not a working YouTube migration or an update**. It must never be added to an A/B catalog as a functional migration. The production APK, version, signing key and updater remain unchanged.

The goal is one installed APK containing its own authentication and Google service implementations. Bundling an installer for a second APK does not meet that goal. No external microG package is installed by this experiment.

## Why a separate module

The exact YouTube base contains 49,728 DEX classes; the pinned GmsCore APK contains 33,098. Their intersection contains 528 duplicate class names. A direct DEX merge cannot load these implementations reliably. Blindly relocating every Google class also breaks parcelables whose class names are encoded in Bundles, including authentication `TokenData`.

The experiment retains upstream class names in an isolated class loader and runs components inside a private process of the **same installed package and UID**. A separate resources context uses public Android APIs. Cross-process Binder parcels use the caller's own classes; Binder interface descriptors must stay unchanged. This design must prove actual component creation and resource access before touching YouTube's launcher or login flow.

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

The pinned upstream `AuthResponse.toString()` includes credentials, and `AuthManagerServiceImpl` logs responses. Remove credential logging in the production module before running any authenticated test. The probe supplies no credentials and tests only local component operations; its logcat artifacts must never contain a real sign-in session.

## Release acceptance

Before enabling the module in YouTube, test the signed candidate on recent Android versions without either `app.revanced.android.gms` or `com.mgoogle.android.gms` installed. Verify component ownership, fresh login with an authorized test account, subscriptions, playback, token refresh, account removal, notification delivery, and a signed in-place upgrade. Also test coexistence with official Play Services and separately installed microG. Keep credentials and tokens out of CI logs and artifacts.

The existing `scripts/runtime-check.py` installs external microG. Its success cannot establish that YouTube works independently. The component experiment has a separate workflow and report; it must explicitly report authenticated testing as unperformed until real evidence exists.

Any eventual automatic release goes to A. B still requires an explicit request, and A includes newer B releases as specified in the repository's `AGENTS.md`.
