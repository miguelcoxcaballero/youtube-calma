# Native compatibility of the embedded backend

This is a static audit of the original pinned GmsCore APK, not a claim that login,
notifications or every native feature works. The binary and matching source are
recorded in [upstream.lock.json](upstream.lock.json):

- APK SHA-256: `62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65`
- Source commit: `80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c`
- Release: ReVanced GmsCore `v0.3.13.2.250932`, Android versionCode `250932004`.

## ARM64 ELF alignment

Python ELF-header inspection and `readelf -lW` agree on every `PT_LOAD` segment:

| Bundled `lib/arm64-v8a/` library | Segment alignment |
| --- | ---: |
| `libc++_shared.so` | 4,096 bytes |
| `libconscrypt_gmscore_jni.so` | 4,096 bytes |
| `libcronet.102.0.5005.125.so` | 4,096 bytes |
| `libimage_processing_util_jni.so` | 4,096 bytes |
| `libmapbox-gl.so` | 4,096 bytes |
| `libopencv_java4.so` | 16,384 bytes |

The five 4 KiB libraries also have load segments whose file offsets and virtual
addresses are not congruent modulo 16 KiB. They are not native 16 KiB-aligned ELF
builds. OpenCV passes these alignment checks; that alone does not prove it works.
APK ZIP alignment is a separate property: repackaging or `zipalign` cannot repair
these ELF layouts. Device compatibility modes have not been assessed.

Reproduce against the pinned APK; this needs Python 3 and GNU `readelf`:

```sh
python3 - /path/to/app.revanced.android.gms-250932004-signed.apk <<'PY'
import hashlib, pathlib, struct, subprocess, sys, tempfile, zipfile
apk = pathlib.Path(sys.argv[1])
assert hashlib.sha256(apk.read_bytes()).hexdigest() == (
    '62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65')
with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as directory:
    for name in sorted(archive.namelist()):
        if not name.startswith('lib/arm64-v8a/') or not name.endswith('.so'):
            continue
        data = archive.read(name)
        assert data[:6] == b'\x7fELF\x02\x01'  # ELF64, little endian
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        loads = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
                 for i in range(count)]
        loads = [segment for segment in loads if segment[0] == 1]
        print(name, 'alignments:', [segment[7] for segment in loads],
              '16KiB offset/address congruent:',
              all(segment[2] % 16384 == segment[3] % 16384 for segment in loads))
        path = pathlib.Path(directory) / pathlib.Path(name).name
        path.write_bytes(data)
        for line in subprocess.check_output(['readelf', '-lW', str(path)], text=True).splitlines():
            if line.strip().startswith('LOAD '):
                print(line.strip())
PY
```

## Authentication, registration and messaging paths

The actual DEX method references agree with these pinned source paths:

- `CheckinClient.request(CheckinRequest)` calls `java.net.URL.openConnection()`
  and uses `HttpURLConnection` ([source, line 54](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-core/src/main/java/org/microg/gms/checkin/CheckinClient.java#L54)).
- `HttpFormClient.request(String, Request, Class)`, used by authentication and
  registration, also uses `URL.openConnection()` ([source, line 38](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-base/core/src/main/java/org/microg/gms/common/HttpFormClient.java#L38)).
- `McsService.connect(int)` calls `SSLContext.getDefault().getSocketFactory()` and
  `SSLSocketFactory.createSocket(Socket, String, int, boolean)`
  ([source, line 446](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-core/src/main/java/org/microg/gms/gcm/McsService.java#L446)).
- `LoginActivity.createWebView(Context)` constructs `android.webkit.WebView`
  ([source, line 264](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-core/src/main/java/org/microg/gms/auth/login/LoginActivity.java#L264)).
  Its renderer comes from the device's WebView provider, not this APK's Cronet library.

Across 673 classes and 4,415 methods under `org.microg.gms.auth`, `checkin`, `gcm`
and `HttpFormClient`, the DEX audit found no native method declarations or direct
`System.load*`/`Runtime.load*` calls. Sixteen Chromium references resolve to D8's
`ProxyChangeListener$$ExternalSyntheticApiModelOutline0`: their relevant bodies
only forward Android APIs such as account visibility and cookie removal. They do
not initialize Cronet. This is direct-call evidence, not whole-program proof.

## Conscrypt and Cronet differ

The actual [`com.google.android.gms.providerinstaller.ProviderInstallerImpl`](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-conscrypt-provider-core/src/main/java/com/google/android/gms/providerinstaller/ProviderInstallerImpl.java#L64)
attempts Conscrypt initialization and installation. Its outer handler catches
`Throwable`. A native linking failure before installation leaves the existing
platform TLS provider available; this is not a general rollback guarantee if an
error occurs after insertion. The legacy `common.security.ProviderInstallerImpl`
delegates to that implementation. In contrast, the common API
[`security.ProviderInstaller.installIfNeeded(Context)`](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-basement/src/main/java/com/google/android/gms/security/ProviderInstaller.java#L28)
is an empty stub; its asynchronous method immediately invokes the listener.
Do not mistake the stub for proof that the actual provider initializes.

The pinned DEX's `CronetLibraryLoader.ensureInitialized(Context,
CronetEngineBuilderImpl)` calls either a supplied native library loader or
`System.loadLibrary("cronet.102.0.5005.125")`, then JNI version/init methods. It has
no Java fallback when loading fails. The APK contains the native engine/provider,
but no `JavaCronetEngine`/`JavaCronetEngineBuilderImpl` or `FallbackCronetProvider`.
Platform HTTP used above therefore does not establish that an optional Cronet
client works without the bundled native library.

## Remaining limits

The libraries above are not demonstrated prerequisites for the audited standard
HTTP, WebView and MCS paths. Optional native features remain unverified, and no
16 KiB-page device result exists. The latest credential-free harness reaches
checkin and a WebView containing an editable field. These results do not prove
authenticated login or identify a network hang.

DroidGuard is reachable from `LoginActivity$JsBridge.getDroidGuardResult(String)`
through [`DroidGuardHandler`](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-core/src/main/kotlin/org/microg/gms/auth/login/DroidGuardHandler.kt#L17).
The handler catches failures, but Google may still require a successful challenge.
The upstream [`HandleProxyFactory`](https://github.com/ReVanced/GmsCore/blob/80bfe22cfaf0de84b6c1e96fe3435877d3b9e08c/play-services-droidguard/src/main/kotlin/org/microg/gms/droidguard/HandleProxyFactory.kt#L101)
loads a separately fetched VM APK with `DexClassLoader` and a null native-library
search path. Its downloaded code and modern Android read-only executable-file
requirements need separate validation. This audit neither bypasses challenge
checks nor claims that every challenged login works. Authenticated playback,
token renewal and notification delivery still require explicit runtime evidence.

The experimental backend patch redirects only the two VM-file writes and the
file permission gate before that loader. The bridge makes files read-only before
writing through its already opened private descriptor, and checks ownership,
regular-file status and read-only mode before loading. `prepare-backend.py`
compares a normalized DEX containing the original `SignatureVerifier`, signed
response `unpack` method and `verifyApkSignature` method before and after adapting
the APK. The build fails if these change. The network response signature check
is real; the upstream `verifyApkSignature` method is a return-true stub. This
patch does not turn that stub into certificate validation, change the signed
challenge bytes or establish that Google will accept a resulting challenge.
