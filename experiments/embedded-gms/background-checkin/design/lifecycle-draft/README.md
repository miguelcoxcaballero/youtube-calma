# Unwired lifecycle research — not an implementation

The files here are incomplete design drafts. They are excluded from `runtime/`,
`stubs/`, the bytecode patch, and the normal test runner. Do not compile, package or
activate them. The existing proposal retains its original 13-hook ABI and
three-argument `McsSessionCoordinator.handleStart`. Same-process MCS restart remains
unresolved; moving these drafts here does not fix it.

Pinned upstream facts (`80bfe22`):

- `McsService.onDestroy()` closes socket resources/database but leaves static
  `rootHandler` and `handlerThread` pointing at the old service instance.
- Both `McsInputStream` and `McsOutputStream` call the root handler's
  `dispatchMessage()` synchronously from their own threads. Joining only the root
  handler thread does not prove old database callbacks have finished.
- Replacing those calls with asynchronous `sendMessage()` is not a harmless fix:
  input reading advances the stream ID, which synchronous message processing may
  include in acknowledgements. Async queuing can change that ordering.
- Capturing/closing streams directly at `onDestroy()` can miss streams subsequently
  published by an in-progress `connect()`. A retirement barrier would need to run
  after the current root-handler callback, then capture old input/output threads,
  close their socket, and join both before closing the old database.
- `McsOutputStream.close()` sets `closed=true` before dereferencing `myHandler`.
  If startup has not assigned the handler yet, the exception is swallowed by
  `McsService.tryClose()`, and the output thread may later enter a live Looper.
  Its handler field is also nonvolatile. Any repair needs an explicit, reviewable
  startup/close handshake; interrupting the thread alone does not quit a Looper.
- `new Socket(host, port)` has no explicit connection timeout. Waiting for genuine
  retirement can remain blocked. A timeout must never authorize resetting shared
  statics while an old thread can still publish or use them.
- `handleAppMessage()` does not register an ordered-broadcast result callback;
  there was no additional asynchronous database user found there. This is a
  narrow source observation, not a complete concurrency proof.

Any future implementation must also defer **all** protocol commands while a new
service awaits initialization, not just reconnect. Replaying Android lifecycle
callbacks is not acceptable. A separate ordinary protocol-dispatch method would
need to preserve Intent contents, flags, order and wakeful-intent completion.
The custom root handler must actually be ready before deferred commands are
dispatched; upstream otherwise treats any pending Intent as CONNECT. A bounded
queue needs an explicit overflow policy and tests for SEND/ACK behavior.

The draft lifecycle helper references methods and `onProtocolReady()` that do not
exist in the current proposal. It does not provide the full ready-handler handshake
or deferred-command integration described above. Its pure state-policy test passed
in isolation, which proves only that small identity/state machine; it does not prove
correct service or network retirement.

Before any activation, require tests covering root/input/output callbacks in flight,
output startup racing close, blocked connect, stopped pending replacements, rapid
restarts, real foreground eligibility, and actual message/ack delivery on Android
35/36. Keep platform lifecycle calls on main; joins and network close operations
must never block main.
