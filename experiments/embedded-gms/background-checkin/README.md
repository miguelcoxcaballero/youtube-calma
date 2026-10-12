# Inactive background checkin proposal

This directory is an opt-in prototype. The existing YouTube build, backend
preparer, manifest and runtime do not use it. Do not publish or enable it merely
because it compiles. It is ready for fresh-process boot experiments after the
integration steps in [HANDOFF.md](HANDOFF.md), with no claim that upstream
same-process MCS restart is repaired. The broader lifecycle draft is inactive.

## Problem and scope

The pinned GmsCore source at `80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c`
starts `CheckinService`, a `dataSync` foreground service, from its checkin
`TriggerReceiver`. Android 15 disallows that service type from `BOOT_COMPLETED`.
The MCS receiver can also emit `android.server.checkin.CHECKIN` when it receives
boot with no registration, so checking only the literal boot action misses the
indirect path. This proposal handles every checkin receiver entry on API 35+;
it never falls back to the prohibited foreground-service launch if scheduling
fails.

`CheckinService.schedule(Context)` also constructs a **service** PendingIntent
whose target is the checkin BroadcastReceiver. On API 35+ the proposal replaces
that scheduler with a persisted, network-constrained JobScheduler job. It requests a 12-hour periodic interval subject to system timing, quotas and
Doze policies; no exact execution time is promised. Failed one-shot
attempts request exponential retry from a three-hour base.

Direct `CheckinService` Binder/callback entry points remain unchanged. In
particular, `LoginActivity` continues to call and await the original synchronous
`CheckinManager.checkin`; it is not turned into an asynchronous job. The same
account permission checks, requests, TLS provider, response handling and signature
validation remain in place.

## Files and integration contract

- `patches/BackgroundCheckinPatch.kt` adds thirteen narrowly matched hooks to the
  prepared module and rejects unexpected method/call counts.
- `runtime/` contains six bridge classes. Compile them with the existing backend
  bridge into the isolated backend classloader. They do not belong in the host's
  ordinary YouTube classloader.
- `stubs/` is compile-only. **Exclude every stub class from all APK/DEX outputs**,
  including its `inhouse.microg.bridge.BackendEnvironment` stub. Compile stubs to
  a separate directory and put that directory on the runtime compiler classpath.
- `manifest-fragment.xml` declares the JobService in `:inhouse_gms`, exported only
  under Android's `BIND_JOB_SERVICE` permission. Add its exact class to the host
  component factory's backend allowlist. The normal isolated backend bootstrap
  must run before service attachment. Reuse host permissions; do not lower SDKs.
- `prepare-proposal.py` writes a separate unsigned, uninstalled module and a
  provenance report. It requires the prior backend report and exact matching
  input/tool hashes. It neither builds nor modifies the installed host APK.

Explicit build example:

```sh
python3 experiments/embedded-gms/background-checkin/prepare-proposal.py \
  --enable-background-checkin \
  --backend /path/to/adapted-gmscore-parcelable.apk \
  --backend-report /path/to/adapted-gmscore-parcelable.report.json \
  --morphe /path/to/pinned-morphe.jar \
  --output /path/to/background-checkin-proposal.apk
```

The patch also independently requires `-Dinhouse.backgroundCheckin=true`.
Neither opt-in is present in the active pipeline.

## Job isolation and cancellation

The scheduler uses namespace `inhouse.embedded.checkin` and IDs `0x49484301`
(trigger) / `0x49484302` (periodic). Before reading or changing jobs it verifies
the real installed service's package, class, process, UID, enabled state,
exported state and binding permission. Before any mutation, both reserved IDs
must be absent or owned by that exact component. It will not replace or cancel
another service's work. No `cancelAll` call is used.

A two-minute job budget applies only on that job's worker thread. Three existing
HTTP calls—checkin, authentication form requests, and user-profile refresh—get
connect/read timeouts of at most 20 seconds and are tracked for cancellation.
Calls outside a job keep their original timeout behavior. Cancellation checkpoints
run at `CheckinManager.checkin` entry, before registration state writes and before
profile writes. A stopped worker cannot start more hooked HTTP work or cross a
later checkpoint. Already completed writes are not rolled back.

Cancellation disconnects tracked connections off the main thread and interrupts
the worker. Java/native DNS or I/O is cooperative, so this is not a promise to
terminate a thread at an exact deadline. The job callback finishes promptly; a
process-wide exclusion remains until the actual worker exits. Upstream's
synchronized `CheckinManager` can still serialize a foreground login behind an
existing request; this needs a cancellation/foreground-continuation device test.

Successful registration retains the upstream account-profile refresh within the
remaining budget. The MCS receiver requests a genuine `specialUse` foreground service during real
boot eligibility, consuming a WAIT action before any zero-ID login or held wake
lock. Successful registration signals the already-promoted instance through its
original protocol handler before profile HTTP work. A job never launches a new
foreground service. An optional focused-Activity entry is available but unwired.
See [MCS-ELIGIBILITY.md](MCS-ELIGIBILITY.md) for exact platform evidence, callbacks,
foreground-notification tradeoffs and remaining lifecycle blockers. Exact-alarm
permission is not treated as automatic background-start authorization.

## Verification so far and required device gates

The patch was compiled and applied to the pinned, previously adapted backend.
All thirteen expected hooks matched and original resource bytes were preserved.
Runtime templates compile against public Android SDK 35. Focused tests cover
service ownership/collisions, job-service binding, opt-in rejection, job-only
network timeouts, cancellation, foreground-session identity and unchanged
foreground network behavior:

```sh
INHOUSE_ECJ=/path/to/ecj.jar python3 -m unittest discover \
  -s experiments/embedded-gms/background-checkin/tests -p 'test_*.py' -v
```

No runtime, authenticated login or push-delivery proof is claimed. Before enabling,
verify on Android 15 and 16:

1. Boot with and without an existing registration, including MCS's indirect
   CHECKIN broadcast; no checkin `dataSync` FGS launch occurs from either path.
2. No network at boot, followed by network restoration; registration resumes
   through JobScheduler without a busy loop or companion package.
3. A job stopped during HTTP; no later registration/profile write occurs after a
   cancellation checkpoint and a subsequent foreground login still completes.
4. Disabled registration cancels only owned jobs; colliding IDs and incorrectly
   declared services fail closed without touching host WorkManager jobs.
5. Registration success, profile refresh, MCS restart under real Android
   eligibility rules, and eventual notification delivery.

API 30–34 receiver behavior is deliberately unchanged by this proposal and has
not been proved by the current API 35/36 emulator results.
