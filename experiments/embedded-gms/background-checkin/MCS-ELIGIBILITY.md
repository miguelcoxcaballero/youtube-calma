# Messaging foreground eligibility: inactive Android 35/36 proposal

This directory is an opt-in experiment, not the production backend. Source audited:
microG `80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c`; pinned APK SHA-256
`62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65`.
Compilation and bytecode application do not establish notification delivery on a device.

## What Android actually permits

`BOOT_COMPLETED` does permit a correctly declared **specialUse** foreground service
on Android 15/16 when the actual boot exemption applies. It forbids a boot-launched
**dataSync** foreground service for target 35+. These are different service types;
checkin must not be relabelled as messaging to avoid the restriction.

- AOSP [Android 15 ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java)
  and [Android 16 ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java):
  `FGS_BOOT_COMPLETED_RESTRICTIONS` (compat ID 296558535) and
  `shouldAllowBootCompletedStart()` consult the actual foreground-start reason and
  `FGS_BOOT_COMPLETED_ALLOWLIST`. The corresponding
  [ActivityManagerConstants](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerConstants.java)
  default allowlist includes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
- [Android 15 boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)
  list the forbidden types. [specialUse requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
  require the type, `FOREGROUND_SERVICE_SPECIAL_USE`, ordinary FGS permission,
  a truthful `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest explanation,
  and a real foreground notification. “No runtime prerequisites” for this type
  does not waive background-start restrictions.

A normal `JobService` does **not** inherit an unlimited boot exemption. In both
AOSP versions, `canBindingClientStartFgsLocked()` explicitly excludes persistent
system-server bindings: its comment says job services should not receive permission
to start background FGS. [JobServiceContext](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/apex/jobscheduler/service/java/com/android/server/job/JobServiceContext.java)
binds regular jobs with `BIND_NOT_FOREGROUND`. A delayed checkin completion therefore
cannot safely start a new MCS foreground service merely because boot scheduled it.

The [exact-alarm exemption](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
describes completing an action the user requested. `canScheduleExactAlarms()` proves
alarm permission, not that an automatic messaging retry satisfies that condition.
This proposal does not use exact-alarm permission as foreground-start authorization.
[Long-running WorkManager workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
also use real foreground services; they do not automatically acquire specialUse
eligibility. [Android 16 job quotas](https://developer.android.com/about/versions/16/behavior-changes-all#job-quota)
still apply while another FGS runs. Keeping an MCS socket indefinitely inside a job
would neither preserve prompt delivery nor avoid those quotas.

## Minimal hybrid and exact upstream hooks

Upstream MCS files below are under `play-services-core/src/main/java/` at the pinned
commit. `ForegroundServiceContext` is under `play-services-base/core/src/main/java/`.

1. `org/microg/gms/gcm/TriggerReceiver.onReceive(Context, Intent)` currently sends a
   checkin broadcast and returns when Android ID is zero. The proposal intercepts
   only the real boot action on API 35+. It requests the existing MCS service as a
   genuine foreground service with `inhouse.microg.action.WAIT_FOR_REGISTRATION`
   and the upstream `foreground=true` marker. Component validation requires the
   host UID, private component, `:inhouse_gms` process, specialUse declaration and
   granted FGS permissions. Android still decides eligibility; no action string,
   UID override, fake lifecycle callback or invented exemption supplies it.
2. `org/microg/gms/common/ForegroundServiceContext.completeForegroundService(...)`
   catches promotion exceptions. Its return is therefore not proof of success.
   The inserted `McsSessionCoordinator.startForeground(Service,int,Notification)`
   calls the existing prepared `BackendEnvironment.startForeground` first and
   records the actual MCS instance only after that platform call returns.
3. `McsService.onStartCommand(Intent,int,int)` receives `handleStart(...)` immediately
   after foreground promotion and before its original synchronized dispatch.
   WAIT is always consumed. This is essential: the original code stores *any*
   pending Intent when `rootHandler` is absent, and `HandlerThread.run()` later
   treats that pending Intent as `MSG_CONNECT`. Waiting with ID zero must never
   enter that path or build a zero-ID login. No socket or wake lock is acquired by
   the waiting branch. The network-constrained, bounded checkin job is scheduled
   only after successful promotion; launch failure may still schedule checkin.
4. Successful job completion checks its cancellation budget, then calls
   `McsSessionCoordinator.onCheckinComplete(Context)`. This only signals an existing
   promoted instance. The new `McsService.inhouseRequestReconnect(): boolean`
   bridge rechecks that instance and nonzero Android ID under `McsService.class`.
   It dispatches original `MSG_CONNECT` (40) with upstream's 5-second wake lock,
   or stores an actual CONNECT Intent for the not-yet-ready handler. It never
   calls `onStartCommand`, promotes a new service, or alters protocol credentials.
5. `McsService.onDestroy()` unregisters the instance. A pure Java
   `ForegroundSessionPolicy` rejects destroyed/replaced instances; the coordinator
   never holds its own lock while calling the protocol bridge. An optional
   `startFromVisibleActivity(Activity)` entry requests the same real service only
   from a focused, live Activity. This helper is **not wired** into an app Activity
   by this proposal and is not proof of foreground-start integration.

An idle, genuinely foreground MCS service can keep its visible notification while
offline and awaiting registration. There is no arbitrary timeout that discards the
boot opportunity merely because Doze or lack of network delayed a job. Its delayed
main-thread check holds no wake lock; it observes disabled preferences or a newly
available registration. Messaging disabled, or checkin disabled while ID is zero,
ends this wait. The tradeoff is an ongoing notification while offline. User/system
service stops remain authoritative.

## Limits and required device gates

- The pinned `McsService.onDestroy()` closes sockets and the database but does not
  quit/reset its static `rootHandler` and `handlerThread`. Those can retain the old
  service/database in a process that survives destruction. The coordinator's
  instance gate does not repair that upstream lifetime problem. Same-process
  stop/restart needs explicit lifecycle work and testing before activation.
- Existing upstream `scheduleHeartbeat()` and `scheduleReconnect()` still use
  exact alarms. Their permission, callback eligibility and later reconnect behavior
  remain separate gates. This hybrid fixes initial boot registration sequencing;
  it does not claim unrestricted automatic foreground restarts after a service is
  killed/stopped or unrestricted alarm access.
- Test real reboot on Android 35 and 36 with zero registration, existing
  registration, airplane mode, network restoration, Doze, notifications allowed
  and denied, messaging/checkin toggled off, real foreground-start denial, user
  stop, process death and same-process restart. Confirm actual foreground
  notification/promotion, no zero-ID CONNECT and no wake lock/socket while waiting.
- Verify checkin cancellation while HTTP is blocked, successful registration before
  profile refresh times out, handler-not-ready reconnect, service destruction racing
  job completion, reconnect after network changes, and actual message/ack delivery.
  Existing protocol retention, package ownership, registration and signature checks
  must remain upstream's. Bounded jobs cannot promise instant push after a stop.
- No authenticated account or production push-delivery result is claimed by the
  source audit, JVM tests or successful patch application alone.
