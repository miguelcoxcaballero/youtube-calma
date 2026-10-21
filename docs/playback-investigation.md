# Playback investigation: position 60 seconds

Status: published to A at the user’s explicit request; playback fix remains unconfirmed.

The user reports that every video stalls around position 60 seconds, including seeking beyond that position. Playback later resumes at low quality without normal quality selection. Background audio stops only when the screen was turned off before that transition. The earlier report that visionOS solved this was explicitly withdrawn. Do not force a visionOS preference migration or claim a separate battery-management cause.

## Confirmed code defect and candidate change

The pinned base APK's `StreamingDataRequest.buildPlayerResponseBuffer` accepts any nonempty adaptive-format list without checking whether those formats contain usable addresses. The candidate response validator rejects lists without URL/signatureCipher fields before the existing fallback loop terminates, removes unaddressable entries, and avoids selecting a singleton low-resolution VP9 ladder over a complete AVC ladder. Unknown protobuf fields are retained. The request field mask now includes playabilityStatus so the existing refusal check can see server responses.

This does not make a server-denied stream accessible. It does not prove that the minute-one failure on the user's account is fixed. No background service, authentication, updater, channel catalog, or published binary was changed.

## Validation and limitations

- Production Java validator: 17 synthetic regression cases passed with ECJ/Java.
- Extension compilation and Kotlin patch-bundle compilation passed. This is not a full patched-APK or runtime validation.
- A credential-free request for public video `aqz-KE-bpKQ` on 2026-10-21 UTC returned zero adaptive formats for Android VR 1.43.32, Android VR 1.61.48 and visionOS 0.1. Statuses were LOGIN_REQUIRED, LOGIN_REQUIRED and UNPLAYABLE respectively; all requested bot-verification sign-in. No account credentials were supplied. This result cannot be extrapolated to the user's authenticated device.
- `Playback diagnostic (no publication)` downloads the exact published 0.1.15 APK, checks its SHA-256, launches an Android 15 emulator and attempts public playback with the display turned off before position 60 seconds. Evidence reports actual media-session positions; a lack of playback is a failure/inconclusive result, never a success. Media-session progression alone does not prove audible output or quality-menu behavior.
- Before release, test the signed candidate beyond 120 seconds, seeking past 60 seconds, quality changes, and screen-off playback starting before 60 seconds. Compare against the baseline with the same account/network. Do not publish this branch as a confirmed fix solely because compilation or synthetic fixtures pass.

Reference leads (not imported patch bundles):
- https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6983
- https://github.com/ReVanced/revanced-manager/issues/3534

## Android baseline result

Run https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/39688388288 completed on 2026-10-21 UTC. The exact 0.1.15 APK installed on Android 15 and its main process remained alive. The public playback probe failed its requirement to demonstrate uninterrupted playback beyond 120 seconds. This is an inconclusive baseline for the user's authenticated failure, not evidence of a successful fix. The run log exposes the structural report; artifact download from GitHub's storage returned HTTP 403 in this workspace, so the detailed session samples and screenshot have not been inspected. Do not infer a specific on-screen error from that result.

The local embedded-runtime suite also passed 38 tests. The first run did not exercise a candidate; see the signed-candidate comparison below for the subsequent result.

## Signed-candidate comparison

The 0.1.16 candidate (Android versionCode 1557278161) built with the permanent signer and passed artifact, manifest, preset, updater and embedded-runtime checks. Both the exact published 0.1.15 baseline and this candidate installed and kept their main processes alive on Android 15. Both displayed bot-verification/sign-in requirements before playback; neither established a measurable media session. Thus neither seeking, quality changes nor background audio could be validated. This is not evidence that the candidate fixes the user's authenticated failure.

Sanitized evidence, hashes and workflow URLs: `validation/playback-0.1.16-candidate.json`. No release or catalog was published. The PR targets main and carries forward the embedded sources without reverting recent catalogs/signing changes; the build entry point delegates to the embedded builder. Publication and the main merge remain pending the user's requested completed fix.

## Publication authorization

The user subsequently explicitly requested “Ponla en main y el A”, with the blocked playback validation already disclosed. Publish this candidate to A after signed upgrade checks; retain the note “Stream validation improvements. Playback fix unconfirmed.” Do not claim authenticated playback, seeking, quality selection or screen-off audio were verified. B remains unchanged.

## Published A candidate

Version 0.1.16 was published after the signed build, Android upgrade/launch matrix and public APK integrity checks passed. The user explicitly authorized main and A publication after the playback limitation was disclosed. See `validation/0.1.16.json`. Release: https://github.com/miguelcoxcaballero/youtube-calma/releases/tag/v0.1.16. B is unchanged.
