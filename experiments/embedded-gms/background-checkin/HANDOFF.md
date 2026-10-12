# Background registration prototype handoff

This is an inactive, separately built experiment. No active build or runtime
consumes these files. The broader MCS lifecycle rewrite was stopped and moved to
`design/lifecycle-draft/`; do not compile or integrate those draft files.

## Exact build chain

| Stage | SHA-256 |
| --- | --- |
| Official ReVanced GmsCore APK | `62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65` |
| Prepared backend including Parcelable hooks | `f2ee13b5f9645e2b988f51a0521b48045f8238d38859210307698add28a032e5` |
| Thirteen-hook background registration module | `f38505f6f01e14f8d55992fa02bddc93e0f29598983927f35b9a98fe494b34bb` |

The latter two files are respectively
`/workspace/microg-embedded-research/adapted-gmscore-parcelable.apk` and
`/workspace/microg-embedded-research/background-mcs-proposal.apk`. Each has a
matching `.report.json`. Upstream source is pinned to
`80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c`. The proposal report verifies its input,
tool and patch hashes, all thirteen matches and 2,067 unchanged resource entries.
Its base retains the Activity saved-state and receiver Intent classloader hooks.
The older `adapted-gmscore-dcl.apk` is not this proposal's input.

## Minimal integration contract

1. Use the proposal APK only as the embedded backend asset. It is unsigned and
   must never be separately installed. Update the host asset hash/provenance
   configuration to that artifact.
2. Compile the six Java files in `runtime/inhouse/microg/bridge/` into the isolated
   backend bridge DEX. Compile `stubs/` separately for type resolution and exclude
   **all** stub classes from runtime output. Keep the existing BackendEnvironment
   implementation and Parcelable helpers.
3. Merge `manifest-fragment.xml`; add
   `inhouse.microg.bridge.CheckinJobService` to the component factory's explicit
   backend allowlist and service initialization route. Its process is
   `:inhouse_gms`; Android binds it under `android.permission.BIND_JOB_SERVICE`.
4. Retain the real MCS service declaration and `specialUse` foreground-service
   permissions/type. Do not suppress its foreground notification. No JobService
   or exact-alarm permission is treated as an exemption to background start rules.
5. For a fresh-process reboot test, no host Activity hook is required: the patched
   real GCM boot receiver requests the real MCS foreground service during Android's
   boot eligibility, then registration uses bounded network-constrained jobs.

The existing bridge ABI is deliberately small:

```text
CheckinJobScheduler.handleReceiver(Context, Intent): boolean
CheckinJobScheduler.handleSchedule(Context): boolean
CheckinJobNetwork.openConnection(URL): URLConnection
CheckinJobNetwork.checkpoint(): void
McsSessionCoordinator.prepareBootReceiver(Context, Intent): boolean
McsSessionCoordinator.startForeground(Service, int, Notification): void
McsSessionCoordinator.handleStart(McsService, Intent, int startId): boolean
McsSessionCoordinator.onDestroy(McsService): void
McsSessionCoordinator.isActive(McsService): boolean
McsService.inhouseRequestReconnect(): boolean
```

`CheckinJobService` calls `McsSessionCoordinator.onCheckinComplete(Context)` after
registration and before profile HTTP work. This only signals an already-promoted
service through the original protocol handler. It does not launch a service or
replay an Android lifecycle callback. Foreground LoginActivity continues its
original synchronous checkin call.

## Optional foreground entry, not implemented in the host

The module provides `McsSessionCoordinator.startFromVisibleActivity(Activity)`.
If later wired, the host must track real `onResume`/`onPause` state and wait for
the resumed Activity's window-focus callback. Recheck the same live Activity's
resume state and `hasWindowFocus()` on the main thread after any asynchronous
backend initialization. An `onCreate` health-check hook alone is insufficient.
Call through the isolated backend classloader with the actual Activity instance.
Deactivate/remove the focus listener on pause/destruction; do not retain stale
Activities or substitute an Application context as the visibility evidence.

The module helper calls `BackendEnvironment.wrap(activity)` before accessing
GcmPrefs/checkin state, so its preferences remain isolated from host preferences.
The helper still requests a real platform foreground-service start; Android can
deny it. No host implementation or Activity patch is included in this handoff.

## Limits and validation

The four focused tests pass: ownership/network/session invariants, runtime
compilation against SDK 35 with stubs excluded, explicit opt-in rejection and
manifest JobService binding. This is not a device, login or push-delivery result.

The pinned upstream MCS service leaves static handlers/threads alive when its
service object is destroyed. A subsequent service instance in the same process
can reference the old service/database. This proposal intentionally does not
repair that upstream lifecycle bug. A stopped process followed by real reboot
starts with fresh static state; a same-process stop/restart must remain a known
unsupported test result, not be described as fixed. See MCS-ELIGIBILITY.md and
the isolated lifecycle draft for details.

Next bounded gate: integrate only into an experimental host, reboot Android 35
and 36 with registration absent/present and with network absent/restored, inspect
actual foreground promotion and absence of prohibited dataSync starts. Check
cancellation and foreground login continuation separately. Authenticated
playback, account migration and notification delivery still require real account
tests; this prototype provides no such proof.
