# Opt-in YouTube host runtime

This source is an experimental same-APK integration, separate from the passing
component probe. It is not a released migration and does not establish Google
login, signed-in playback, token refresh or push delivery.

The host retains YouTube's package, signing key, application and AndroidX factory
in its normal processes. Only `:inhouse_gms` and `:inhouse_gms_unstable` use
`BackendApplication`; declared module components use an isolated platform-parent
class loader. DroidGuard retains the upstream separate-process Binder boundary:
its network response delivery must not share a blocked client UI thread. The module
patch wraps actual component contexts, without subclass replacement components.
The bundled module and bridge DEX are hash verified and privately extracted as
read-only files. Native libraries use the running process ABI. No separate APK is
installed or downloaded. The module resource archive is accessed with the public
`getResourcesForApplication(ApplicationInfo)` overload; the host's real package,
UID and `PackageManager` authentication checks remain intact. The backend
Application keeps Android's original `ContextImpl` base for framework receiver
delivery, while delegating module resources/storage/services to a separate scoped
context; wrapping this Application is idempotent.

Build integration must generate `inhouse.microg.host.EmbeddedGmsBuild` with:

- `public static final String GMS_SHA256` — exact adapted module hash.
- `public static final String BRIDGE_SHA256` — exact bridge DEX hash.
- `public static final int BACKEND_VERSION = 250932004`.
- `public static final String[][] NATIVE_LIBRARIES` — pinned archive paths and
  SHA-256 hashes of the native libraries, verified again after extraction.
- `public static final String[] COMPONENTS` — exact registered module classes.

Only compile `stubs/` to resolve the host AndroidX factory. Never package its
classes: the original APK supplies its real implementation. Host sources belong
in the host extension DEX, bridge sources belong exclusively in
`assets/embedded/bridge.dex`, and the adapted module goes in
`assets/embedded/gmscore.apk`. All module manifest components must run in
`:inhouse_gms`, except `org.microg.gms.droidguard.core.DroidGuardService`, which
must run in `:inhouse_gms_unstable`. Each backend process has its own stable WebView
data-directory suffix; the verified immutable module files and ordinary backend
storage remain shared, as in upstream. Component factories must not route ordinary
YouTube components to the module loader.

The account type is `es.inhouse.youtube.google`; the GServices and accounts
authorities are `app.revanced.android.youtube.inhouse.gms.gservices` and
`app.revanced.android.youtube.inhouse.gms.auth.accounts`. Existing external
microG credentials are neither accessed nor migrated. Module storage is
namespaced. Activities carry their original module theme as integer metadata
`inhouse.gms.theme`, applied through `BackendEnvironment.prepareActivity` before
`onCreate`. Foreground services must declare their real Android 14+ type and
permission, in addition to the `startForeground` bridge.

`YouTubeGmsBridge.check` asynchronously verifies owned registrations, registered
account type, a real GServices query and a live token-service Binder. Success is
component availability only, not proof of authentication or playback.

The health worker prepares the verified payload before acquiring a provider.
Bootstrap logs contain fixed stage names and elapsed milliseconds only. Existing
notification delegation to the exact external ReVanced services package is
cleared during upgrade; host notifications stay enabled. The host manifest also
disables Firebase's external notification delegation. Module foreground and auth
notifications use a small icon that SystemUI can resolve from the installed host.

An explicit instrumentation build may register
`inhouse.microg.host.YouTubeSmokeInstrumentation`, targeting the existing YouTube
package. It needs no additional permission and installs no test APK. The runner
is `../run-youtube-smoke.py --api 35 --apk PATH --output-dir PATH`; API 34 and 36 are
also supported. The experiment requires Android 14 for independent JobScheduler
namespaces. It checks the actual main Application instance, private provider
ownership, account-provider calls, an actual upstream read-only manifest receiver
request/reply, the token Binder, and the separate DroidGuard service registration
and remote broker before opening the private
login activity through Android AccountManager's real add-account response. It
also recreates saved state/Intent extras containing a real module Parcelable and
framework Parcelable to verify their correct loaders. It stops at the Google
email-entry page without entering any
credentials. Its artifacts retain structural checks, exceptions and bootstrap
timings; raw UI text, URLs and upstream log messages are never saved.

Required remaining evidence: clean installations and upgrades on recent Android,
coexistence with official Play Services/external microG, a real authorized Google
sign-in, subscription/channel access, signed-in playback, token renewal, logout,
restart and push delivery. Do not publish as functional without those results.
