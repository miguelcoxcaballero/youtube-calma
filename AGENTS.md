

# Inhouse services updater — mandatory shared updater policy

Canonical repository: https://github.com/miguelcoxcaballero/inhouse-services-updater

Every AI/developer working in this app must read this section even without opening the updater repository:

- All new changes and automatic releases go to **A** (rapid changes/testing).
- **B** (reviewed releases) requires the user's **explicit request**. Tests passing is not permission to promote.
- Every release needs a tiny changelog: preferably 2–10 words, maximum 120 characters.
- Never independently edit updater copies. Change the canonical repository first, test, commit, and sync the SAME revision into YouTube, Instagram, Notes, Photos and Read using its `tools/sync.py`.
- Commit `inhouse-updater.lock.json` and shared files together. Build checks must reject drift. App identity and host integration are the only permitted differences.
- Updates are optional. Later snoozes automatic prompts for 14 days; manual updates remain available in settings. The popup and settings both expose A/B selection.
- Keep APK package and signing key; increment Android versionCode globally across A/B. Switching channels never downgrades or deletes user data.
- Publish each exact signed APK before its matching channel catalog. Use `updates/A.json` and `updates/B.json`; never mix channels using generic GitHub latest. B initially has no releases until explicitly requested.
- See the central `AGENTS.md`, `docs/PROTOCOL.md` and `tools/catalog.py` before changing publication logic.

This section supersedes older instructions that mandate blocking update gates, independently copied updaters, or unconditional latest/stable publication. Existing signature and integrity requirements remain in force.

# APK release identity (regression in 0.1.6)

`CalmaBuild.java` is the sole release version/code source. The patch compiler generates its constants; never put an independent release code in `YouTubeCalma.kt`. Before advertising an update, decode the SIGNED APK and verify its Android versionCode and `es.calma.version` metadata against the catalog. `adb install -r` can accept the same code and is insufficient: require a strictly greater actual code than the previous APK. Run `tests/artifact.py` and the Android upgrade-from-0.1.5 check. Keep the shared installer's strict version, hash, package and signature checks unchanged.
