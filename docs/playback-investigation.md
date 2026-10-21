# Playback investigation: position 60 seconds

Status: investigation, not a verified fix or published release.

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
