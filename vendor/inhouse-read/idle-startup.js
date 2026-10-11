/**
 * Start-up work that is not needed to show the shelf waits here: it runs in
 * idle slices once the live shelf has presented a frame (or after a hard
 * deadline), so the first frame never shares the main thread or the network
 * with the offline-shell download, optional chunks or update checks.
 */
const SHELF_SELECTOR = 'canvas.ihr-bookshelf-scene'
const FIRST_FRAME_DEADLINE_MS = 8000

let presented = null
let presentedDocument = null
/** Resolves once the shelf canvas has drawn, or after the deadline (e.g. no WebGL). */
export function shelfPresented() {
  const ownerDocument = globalThis.document
  if (!ownerDocument) return Promise.resolve(false)
  if (presentedDocument !== ownerDocument) { presented = null; presentedDocument = ownerDocument }
  return presented ||= new Promise(resolve => {
    const started = performance.now()
    const check = () => {
      // A discarded document cannot present a frame or own startup tasks.
      if (globalThis.document !== ownerDocument) return resolve(false)
      const canvas = ownerDocument.querySelector(SHELF_SELECTOR)
      if (Number(canvas?.dataset.renderCount) > 0 || performance.now() - started > FIRST_FRAME_DEADLINE_MS) return resolve(true)
      setTimeout(check, 100)
    }
    check()
  })
}

export function idleSlice({ timeout = 2000 } = {}) {
  return new Promise(resolve => {
    if (typeof requestIdleCallback === 'function') requestIdleCallback(() => resolve(), { timeout })
    else setTimeout(resolve, 50)
  })
}

/** Runs the tasks one per idle slice after the first live frame; a failing task never stops the rest. */
export async function runAfterFirstFrame(tasks) {
  const ownerDocument = globalThis.document
  if (!await shelfPresented()) return
  for (const task of tasks) {
    await idleSlice()
    if (globalThis.document !== ownerDocument) return
    try { await task() } catch (error) { console.warn('Carga diferida omitida:', error) }
  }
}
