


# Embedded microG build source

This tree contains the embedded-microG release sources, carried forward from `v0.1.15`. Build with `experiments/embedded-gms/build-youtube.py`; `scripts/build.py` forwards to the same builder. Do not restore the external-microG builder from older main commits. The signed-release workflow stages artifacts and validates them before publication.

The APK requires Android 14+. Switching from external microG requires a fresh Google sign-in. Automated component checks are distinct from authenticated playback, token renewal and push delivery. Consult both public catalogs before selecting the next global versionCode; a source version is not proof of publication. Keep the original package and permanent signing key. Keep `app/assets/updates/config.json` installedVersion equal to `CalmaBuild.VERSION`; the embedded builder and artifact checks enforce this. The user explicitly authorized merging main and publishing the 0.1.16 candidate to A despite blocked authenticated-playback validation. Preserve this limitation in its release notes; see `docs/playback-investigation.md`.

The 0.1.14 catalog changelog is also the pre-update notice shown by existing clients: “Android 14+. Built-in microG: sign in again after updating. If sign-in fails, wait 30 minutes and retry.” It must be visible before Update, not hidden in Details. This is release metadata, not a fork of the canonical updater. Never rewrite an older release's immutable changelog. The 30-minute retry guidance is the user's requested advice, not a guarantee that Google will approve a later attempt. Existing embedded-microG accounts from 0.1.13 are retained; this fresh-login notice addresses the migration from external microG.

# APK release identity (regression in 0.1.6)

`CalmaBuild.java` is the sole release version/code source. The patch compiler generates its constants; never put an independent release code in `YouTubeCalma.kt`. Before advertising an update, decode the SIGNED APK and verify its Android versionCode and `es.calma.version` metadata against the catalog. `adb install -r` can accept the same code and is insufficient: require a strictly greater actual code than the previous APK. Run `tests/artifact.py` and the Android upgrade-from-0.1.5 check. Keep the shared installer's strict version, hash, package and signature checks unchanged.

## Signing Inhouse Learn here

The permanent Inhouse signing key (certificate SHA-256 `7133a4b3e4b9f9c4d2fbbd38b7dd2d44c7b8d0d7d16568bfe3da74a09e7887b6`) exists only as the secret `EJEMPLO` of this repository and of `ig-calma`. GitHub cannot copy a secret, so `inhouselearn` has none: `.github/workflows/sign-inhouse-learn.yml` builds the inhouselearn commit given as `ref`, signs it with `EJEMPLO`, verifies the signer and stages it as the public prerelease `learn-signed-<version>-<short sha>` of this repository (only the newest is kept). inhouselearn's own "Android app" workflow then verifies and publishes it as `android-v<version>` there. Never move the key anywhere else, never sign anything but an inhouselearn commit with that workflow, and keep the YouTube release workflows untouched by it.

# Inhouse services updater — mandatory shared updater policy

Canonical repository: https://github.com/miguelcoxcaballero/inhouse-services-updater

Every AI/developer working in this app must read this section even without opening the updater repository:

- **B is the default update channel.** A requires an English development warning, explicit acknowledgement and slide confirmation. Legacy implicit A preferences migrate to B; choosing B clears A consent. This does not authorize publishing or promoting a B release.
- New changes and automatic releases go to **A** unless the user explicitly requests B.
- **A receives all A and B releases; B receives only B releases.** The highest global Android versionCode wins: a newer B supersedes an older A for A users, while an older B never displaces a newer A.
- **B** (reviewed releases) requires the user's **explicit request**. Tests passing is not permission to publish or promote. B may promote an existing A release or publish a new verified APK directly; a previous A publication is not required.
- Every release needs a tiny changelog: preferably 2–10 words, maximum 120 characters.
- Never independently edit updater copies. Change the canonical repository first, test, commit, and sync the SAME revision into YouTube, Instagram, Notes, Photos and Read using its `tools/sync.py`.
- Commit `inhouse-updater.lock.json` and shared files together. Build checks must reject drift. App identity and host integration are the only permitted differences.
- Updates are optional. Later snoozes automatic prompts for 14 days; manual updates remain available in settings. The popup and settings both expose A/B selection.
- Keep APK package and signing key; increment Android versionCode globally across A/B. Switching channels never downgrades or deletes user data.
- Publish each exact verified signed APK before its matching channel catalog. Never replace a published binary or its immutable metadata. Use `updates/A.json` and `updates/B.json`.
- The publisher mirrors every B release and its exact metadata into A, preserving a newer A as latest. Existing clients therefore receive B releases through A without an APK update. B never falls back to A; neither channel uses generic GitHub latest. B initially has no releases until explicitly requested.
- See the central `AGENTS.md`, `docs/PROTOCOL.md` and `tools/catalog.py` before changing publication logic.

This section supersedes older instructions that mandate blocking update gates, independently copied updaters, unconditional latest/stable publication, disjoint A/B catalogs, or publication in A before an explicitly requested B release. Existing signature and integrity requirements remain in force.
