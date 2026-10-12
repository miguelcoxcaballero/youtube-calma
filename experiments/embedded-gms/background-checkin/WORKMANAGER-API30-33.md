# WorkManager isolation on API 30–33: inactive proposal

**A reserved ID range alone does not make the backend safe on API 30–33.** These
versions have no `JobScheduler.forNamespace`. Host and backend share the installed
UID and the default job-ID namespace, despite separate processes, databases and
class loaders. The API 34+ `inhouse.microg` namespace solution cannot be backported
by choosing large integers. Nothing in this document enables a fallback.

## Pinned evidence

Audited the APK SHA-256 and source commit in [../upstream.lock.json](../upstream.lock.json).
The [pinned source declares WorkManager **2.7.0**](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/build.gradle#L27) (`workVersion`); the
following signatures and control flow were checked in the actual APK DEX, not
inferred from a newer AndroidX version. JADX can rename extracted packages because
of metadata: the actual descriptors below are authoritative.

```text
Landroidx/work/WorkManagerInitializer;->create(Landroid/content/Context;)Landroidx/work/WorkManager;
Landroidx/work/Configuration$Builder;-><init>()V
Landroidx/work/Configuration$Builder;->setJobSchedulerJobIdRange(II)Landroidx/work/Configuration$Builder;
Landroidx/work/Configuration$Builder;->build()Landroidx/work/Configuration;
Landroidx/work/impl/utils/IdGenerator;->nextJobSchedulerIdWithRange(II)I
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->schedule([Landroidx/work/impl/model/WorkSpec;)V
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->scheduleInternal(Landroidx/work/impl/model/WorkSpec;I)V
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->cancel(Ljava/lang/String;)V
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->cancelJobById(Landroid/app/job/JobScheduler;I)V
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->cancelAll(Landroid/content/Context;)V
Landroidx/work/impl/background/systemjob/SystemJobScheduler;->reconcileJobs(Landroid/content/Context;Landroidx/work/impl/WorkManagerImpl;)Z
```

The manifest's `androidx.startup.InitializationProvider` includes
`androidx.work.WorkManagerInitializer`. Its typed `create(Context)` calls
`WorkManager.initialize(context, new Configuration.Builder().build())`; its
`create(Context):Object` method is only the synthetic bridge. The builder defaults
to **0 through `Integer.MAX_VALUE`**. `setJobSchedulerJobIdRange` accepts a range
only when `max - min >= 1000` and stores both inclusive endpoints.

`IdGenerator` stores `next_job_scheduler_id` in the backend WorkDatabase
`Preference` table. It reads/increments that value transactionally, wraps after
`Integer.MAX_VALUE`, and resets an out-of-range value to the configured minimum.
Its class lock is local to its class loader/process. It never checks Android's
pending jobs or another allocator. The `SystemIdInfo` table maps
`work_spec_id` to `system_id`.

`SystemJobScheduler.schedule` reuses an existing `SystemIdInfo.systemId` even if it
is outside a newly configured range. Otherwise it allocates an ID and inserts the
mapping **before** `scheduleInternal` calls the OS. The actual OS calls are through
`WorkManagerImpl$$ExternalSyntheticApiModelOutline0` overloads:

```text
m(Landroid/app/job/JobScheduler;Landroid/app/job/JobInfo;)I  -> JobScheduler.schedule
m(Landroid/app/job/JobScheduler;I)V                      -> JobScheduler.cancel
m(Landroid/app/job/JobScheduler;)Ljava/util/List;         -> JobScheduler.getAllPendingJobs
```

WorkManager's `getPendingJobs` filters by exact `ComponentName(context,
SystemJobService.class)`. Its `cancel(workSpecId)` additionally matches
`EXTRA_WORK_SPEC_ID`; its `cancelAll(Context)` cancels each filtered ID, not the
platform's unfiltered `cancelAll()`. `reconcileJobs` also uses this component
filter. It cancels malformed matching jobs and resets backend work to unscheduled
when expected work IDs are missing. The prepared backend's service class is
`inhouse.microg.module.androidx.work.impl.background.systemjob.SystemJobService`;
the host's original service must remain a different component.

## Viable integration route, still unimplemented

1. In the **backend typed initializer only**, set the range before its existing
   builder's `build()` when `30 <= SDK_INT <= 33`. For example,
   `0x49481000..0x49481fff` supplies 4,096 candidate IDs, separate from this folder's
   checkin IDs. This is a proposed allocation convention, not an OS reservation.
   Keep other builder settings and the normal initializer order. Calling
   `WorkManager.initialize` again after AndroidX Startup is too late and can throw.
   `Configuration.Provider` only helps when automatic initialization is disabled
   and the correct isolated Application implements that backend-loaded interface.
2. Keep the backend WorkDatabase, its `Preference` counter and legacy
   `androidx.work.util.id` preferences separate from the host. Validate the prepared
   service's real package, class, `:inhouse_gms` process and `BIND_JOB_SERVICE`
   permission through PackageManager. No fabricated package or UID is appropriate.
3. Allocate under the backend DB transaction and inspect **unfiltered** OS pending
   jobs. Reuse an ID only for the exact backend service **and same work-spec ID**.
   Skip IDs occupied by another service or another backend work item. On query
   failure, exhaustion or invalid service metadata, defer without scheduling or
   cancelling an unknown job. Existing mappings outside the range need a scoped
   migration; changing the builder does not migrate them.
4. Recheck the exact component and work-spec identity immediately before each
   schedule/cancel operation, including cancellation from reconcile/cancel-all.
   Never use an unfiltered `JobScheduler.cancelAll`. Keep all modifications local
   to the backend's DB and matching jobs.
5. Make scheduling refusal a real recoverable DB outcome. Merely returning
   `RESULT_FAILURE` from an OS-call wrapper is insufficient: `Schedulers.schedule`
   already marks `WorkSpec.scheduleRequestedAt`, and `SystemJobScheduler.schedule`
   already inserted its ID mapping. `scheduleInternal` returns `void`, catches most
   failures and may commit this state. Restore the affected backend work to
   unscheduled, reconcile only its own mapping, and arrange a bounded retry rather
   than silently stranding it or spinning on a conflicting ID.

There remains a **check-then-act race**: Android exposes no atomic
“schedule/cancel only if this ID still belongs to this component” operation on
API 30–33. Another host process can take an ID between the ownership check and
mutation, or replace a backend job later. Host WorkManager's default integer range
includes every proposed positive block. An unrelated host `JobScheduler.cancelAll`
can also remove backend jobs. Complete protection therefore requires cooperation
from **every host allocator/canceller**: disjoint configured ranges plus a common
cross-process allocation policy, migration of old IDs and ownership guards.
A backend-only lock or range cannot establish that guarantee.

Use API 34+ namespace isolation as the baseline for background coexistence; it
still needs its own runtime checks. Until the older-API coordination is implemented
and tested, keep this fallback inactive;
API 30–33 background WorkManager isolation is unverified. This does not authorize
suppressing platform restrictions or changing account/TLS/signature checks.
Required tests include pre-existing host IDs inside the block, stale backend
mappings, allocation wrap/exhaustion, failed OS enumeration/scheduling, cancel and
reconcile ownership, concurrent host/backend scheduling, shared job quotas and
process restart. No such runtime result is claimed here.
