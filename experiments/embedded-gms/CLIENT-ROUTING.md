# YouTube client routing: pinned APK observations

This is a static inspection of the user-provided APK, not evidence of working
embedded authentication. Obfuscated names below apply only to this exact input:

- Package: `app.revanced.android.youtube`
- Version: `20.40.45`, Android version code `1557278144`, target SDK `35`
- SHA-256: `220c4eb6d315a8887ef404d6530b16196701b84848e568360b0b58bdbe3103f2`
- Source identity: [`../../base.lock.json`](../../base.lock.json)

Methods were located in the actual DEX using `scripts/DexFind.java`, extracted
with dexlib2 and inspected with JADX 1.5.6. Entries describe observed code;
adaptations are requirements for the experiment, not implemented claims.

| DEX class/method | Observed behavior | Embedded integration requirement |
| --- | --- | --- |
| `Lpxy;-><clinit>()V` | Creates `ComponentName("app.revanced.android.gms", "com.google.android.gms.auth.GetToken")`; account type list includes `app.revanced`. | Route the installed package to the host and register a matching component. If the implementation class changes, update the explicit component class separately. |
| `Lpxy;->i(Context,ComponentName,Lpxx;)Object` | Obtains the connection supervisor and binds the explicit token component, then passes its Binder to the token operation. | Test the real returned Binder and token callback, not just service resolution. |
| `Lpxy;->a(Context,Bundle)V` | Sets `clientPackageName` and, when absent, `androidPackageName` from `context.getApplicationInfo().packageName`. | Preserve the actual installed caller package. Overriding only `Context.getPackageName()` does not alter this path. Do not replace these extras with a fictional external package. |
| `Lpxy;->b(Context,Account,String,Bundle)TokenData` | Validates the account, copies options, adds the caller extras above, then obtains token data through the explicit component. | Preserve account type, caller extras and the wire protocol together. A working login activity alone does not establish token retrieval. |
| `Lpxy;->c(Context,String)String` | Constructs `Account(name,"app.revanced")` and requests `^^_account_id_^^`. | Account-type changes must apply to account construction as well as authenticator declarations. |
| `Lpxy;->j(Context)Account[]` | Acquires `app.revanced.android.gms.auth.accounts` and calls `get_accounts` with argument `app.revanced`; consumes a parcelable `accounts` array. | Redirect the provider authority and account type together. Verify provider ownership, caller identity and returned framework `Account` objects. |
| `Lqiz;->a(Context)I` | Returns external GmsCore's installed Android `versionCode`. | Mapping the package to YouTube returns YouTube's unrelated release code. Use a narrowly scoped backend-version adapter when this value controls GMS feature behavior. |
| `Lpxy;->h(Context,String,String,Intent,PendingIntent)V` | Uses `qiz.a()` and threshold `233800000` to diagnose a missing recovery `PendingIntent`; throws a recoverable auth exception. | Host version-code substitution can select a different recovery expectation. Verify recovery intent and pending intent from the embedded token service. |
| `Lqiz;->c(Context)Context` | Calls `createPackageContext("app.revanced.android.gms",3)`. | Replacing only the package literal with the host produces YouTube's resources and class loader, not an isolated microG context. Adapt this lookup where used. |
| `Lqiz;->h(Context)Z` | Checks installation sessions and `getApplicationInfo(gms,8192).enabled`. | An installed host is not proof that its embedded components are initialized or available. |
| `Lqiz;->e(Context)Z` and `Lqja;->d(PackageInfo,Z)Z` | Query GmsCore package signatures and distinguish Google Play/GMS package names during signature classification. | Keep backend identity checks separate from host signing identity; do not globally reinterpret every host package query as GMS. |
| `Lqmq;->e(Context,String)String` | Loads `getResourcesForApplication("app.revanced.android.gms")`, then `getIdentifier(name,"string","app.revanced.android.gms")`. | Package routing alone cannot expose resources kept inside an embedded APK. Supply the isolated backend resources and their actual resource package. |
| `Lqif;->a(Bundle)Lrju;` | Targets intents at `app.revanced.android.gms`; chooses `com.google.iid.TOKEN_REQUEST` or `com.google.android.c2dm.intent.REGISTER`. | Route to the corresponding embedded service/receiver; retain protocol action names and callback delivery. |
| `Lcom/google/android/gms/common/internal/GetServiceRequest;` constructor | If the supplied package equals `app.revanced.android.gms`, stores that same literal; otherwise preserves the supplied string. | This branch only normalizes the identical string. It does not convert YouTube's caller identity into GMS. Preserve the real caller in service requests. |
| `Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;->onCreate(Bundle)V` | Invokes `GmsCoreSupport.checkGmsCore(Activity)`. | Replace the external dependency check only when the embedded health check exists. Hiding the prompt does not supply services. |
| `Lapp/revanced/extension/shared/GmsCoreSupport;` | Vendor group is `app.revanced`; checks external package installation, `content://app.revanced.android.gsf.gservices/prefix`, battery restrictions and upstream updates. | Replace the external lifecycle assumptions with embedded status and the existing app updater. |

`Lqiz;->d(Context,I)V` is already an empty method in this base. That does not
remove the explicit token service, account provider or package-context paths.
JADX could not reconstruct `qiz.b(Context,int)` reliably; no behavioral claim
about that method is based on its failed Java output.

## Backend boundary relevant to a shared installed package

The upstream source reviewed for this experiment is ReVanced/GmsCore commit
`776a8fb7a3887fdee24df854b673dc79df5c9da7`.

- `play-services-core/src/main/java/com/google/android/gms/auth/GetToken.java`
  returns `AuthManagerServiceImpl` from `onBind`.
- `AuthManagerServiceImpl.getTokenWithAccount` reads `androidPackageName` or
  `clientPackageName`, then calls `PackageUtils.getAndCheckCallingPackage`.
- `play-services-base/core/src/main/java/org/microg/gms/common/PackageUtils.java`
  validates suggested UID/PID against Binder identity and checks the package
  against packages belonging to the real UID. With one installed host package,
  inventing an external backend package in the caller extras fails this boundary.
- `AuthManager.buildTokenKey` includes caller package and signature in its cache
  key. Do not silently substitute a service package for the caller or assume the
  old external authenticator's token storage migrates with code.
- `PackageSpoofUtils` reads package metadata keyed by the configured microG
  package and caches by real package. The supplied base already contains its
  YouTube protocol metadata. One real installed package cannot represent two
  different metadata identities through an unqualified package-wide lookup.
  Backend-internal lookups and YouTube caller lookups need distinct, scoped
  handling. Do not weaken the UID check to make a mismatch disappear.

Keep Binder descriptors such as `com.google.android.auth.IAuthManagerService`
and `com.google.android.gms.common.internal.IGmsServiceBroker` unchanged.
Implementation class names, Android component names and Binder wire descriptors
are different contracts. An isolated class loader avoids duplicate definitions
in the host loader, but does not itself solve parcelable class loading, resources,
component lifecycle or package identity.

## Reproduce the static evidence

With local APKs and Android `aapt2`, no credentials or network are required:

```sh
python3 scripts/audit-embedded-gms.py \
  --host base/youtube-original.apk \
  --microg /path/to/gmscore.apk \
  --aapt2 /path/to/build-tools/aapt2 \
  --output build/gms-integration-audit.json
python3 -m unittest discover -s tests -p test_embedded_gms_audit.py -v
```

The previously used official microG binary,
`app.revanced.android.gms-250932004-signed.apk`, has SHA-256
`62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65`.
It contains 33,098 classes; the base has 49,728, with 528 definition collisions.
This binary is the historical CI dependency, not a claim that it matches the
newer source commit above. The audit records the actual provided APK identities.

The runner `scripts/runtime-check.py` currently downloads and installs that
external dependency before testing. Those results cannot prove independence.
The audit flags that installation and keeps `functionalMigrationVerified=false`.
`--candidate` can detect residual literal references or missing original Binder
descriptors; it cannot establish authentication correctness from string presence.

Runtime validation remains necessary on a device without external microG:
component/provider ownership, Binder and parcelable round trips, sign-in/token
recovery, authenticated subscriptions/playback, background notification delivery,
upgrade behavior, and coexistence with a separately installed old authenticator.
