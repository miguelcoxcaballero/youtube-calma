


# Embedded microG release source (0.1.13 · A)

The user explicitly authorized the experimental embedded-microG APK on A. Its source is tag `v0.1.13`, commit `61ff3d3bce37e72b6e628bd9c6be821ef7243dfc` on `release-youtube-0.1.13-a`; use that checkout and `experiments/embedded-gms/build-youtube.py` for subsequent embedded builds. `main` retains the external-microG build sources and publishes the channel catalogs. Do not rebuild a future A from `main/scripts/build.py` and silently restore the companion-app dependency. Integrate the embedded source deliberately before changing its runtime.

The experimental APK requires Android 14+ and a fresh Google sign-in. Credential-entry/component checks are distinct from completed sign-in, authenticated playback, token renewal and push delivery; those real-account behaviors remain unverified. The release does not promote the experiment to B. Consult both public catalogs before selecting the next global versionCode; `main`'s older `CalmaBuild.java` is not the latest A release source. Keep the original package and permanent signing key. Keep `app/assets/updates/config.json` installedVersion equal to `CalmaBuild.VERSION`; the embedded builder and artifact checks enforce this.

# APK release identity (regression in 0.1.6)

`CalmaBuild.java` is the sole release version/code source. The patch compiler generates its constants; never put an independent release code in `YouTubeCalma.kt`. Before advertising an update, decode the SIGNED APK and verify its Android versionCode and `es.calma.version` metadata against the catalog. `adb install -r` can accept the same code and is insufficient: require a strictly greater actual code than the previous APK. Run `tests/artifact.py` and the Android upgrade-from-0.1.5 check. Keep the shared installer's strict version, hash, package and signature checks unchanged.

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
