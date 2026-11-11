package org.futo.inputmethod.latin.tapswipe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.keyboard.Keyboard
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Records every touch and every keyboard decision into a JSON Lines file, for the typing test and
 * the typing simulator. See `docs/plans/TYPING_SIMULATOR.md`.
 *
 * ## When it records
 *
 * Only while a recording is open **and** the focused field carries the marker
 * `org.futo.inputmethod.latin.tapswipe.Record=<id>` in `EditorInfo.privateImeOptions`. No other field
 * is ever recorded. The typing test screen sets the marker on its own field. The simulator's driver
 * app sets it on its field, and the keyboard then opens the recording by itself.
 *
 * ## Cost on the touch path
 *
 * Every hook first reads [active], one volatile boolean. When it is false, nothing else runs. When it
 * is true, the caller builds one short string on the main thread, and a single background thread does
 * the file write. The main thread never touches the file.
 *
 * ## Time base
 *
 * Every line has `t`, from `SystemClock.uptimeMillis`. MotionEvent times use the same clock, so
 * touches and decisions line up.
 */
object TypingTestRecorder {
    private const val TAG = "TypingTestRecorder"
    const val MARKER = "org.futo.inputmethod.latin.tapswipe.Record"
    const val DIR = "recordings"

    @Volatile
    private var recording = false

    @Volatile
    private var fieldMatches = false

    /** True when hooks should emit. Read this before building an event. */
    @JvmStatic
    val active: Boolean get() = recording && fieldMatches

    @Volatile
    var currentId: String? = null
        private set

    var currentFile: File? = null
        private set

    private var writer: BufferedWriter? = null
    private var executor: ExecutorService? = null

    private var lastKeyboardView: WeakReference<View>? = null
    private var lastKeyboard: WeakReference<Keyboard>? = null
    private var lastPaddingLeft = 0
    private var lastPaddingTop = 0
    private var lastVerticalCorrection = 0f

    /** Builds the marker a field puts in `privateImeOptions`. */
    @JvmStatic
    fun markerFor(id: String): String = "$MARKER=$id"

    /** @return the recording id in [options], or null */
    @JvmStatic
    fun idFrom(options: String?): String? {
        if (options == null) return null
        for (part in options.split(',', ';', ' ')) {
            val p = part.trim()
            if (p.startsWith("$MARKER=")) return p.substring(MARKER.length + 1).takeIf { isSafeId(it) }
        }
        return null
    }

    /** Ids become file names, so only letters, digits, `-` and `_` are allowed. */
    @JvmStatic
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 80 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Opens a recording. A second call with the same id keeps the open file. A different id closes
     * the old file first.
     */
    @Synchronized
    @JvmStatic
    fun start(context: Context, id: String): File? {
        if (!isSafeId(id)) return null
        if (recording && id == currentId) return currentFile
        stop()
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "$id.jsonl")
        return try {
            writer = BufferedWriter(FileWriter(file, true), 64 * 1024)
            executor = Executors.newSingleThreadExecutor { r -> Thread(r, "TypingTestRecorder") }
            currentId = id
            currentFile = file
            recording = true
            TapSwipeSession.resetListener = { reason, strokes, gen ->
                TypingTestEvents.sessionReset(reason, strokes, gen)
            }
            emit(event("recording_start").put("id", id).put("file", file.name))
            scheduleLayout("recording_start")
            file
        } catch (e: Exception) {
            Log.e(TAG, "could not open $file", e)
            null
        }
    }

    /**
     * Closes the recording only if [id] is still the open one. An activity that is being replaced
     * is destroyed after its replacement starts, and must not close the new recording.
     */
    @Synchronized
    @JvmStatic
    fun stopIf(id: String): File? = if (recording && currentId == id) stop() else null

    /** Closes the recording, after every queued line is written. */
    @Synchronized
    @JvmStatic
    fun stop(): File? {
        if (!recording) return null
        emit(event("recording_stop"))
        recording = false
        val ex = executor
        val w = writer
        ex?.execute { try { w?.flush(); w?.close() } catch (_: Exception) {} }
        ex?.shutdown()
        try { ex?.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        executor = null
        writer = null
        val f = currentFile
        currentId = null
        return f
    }

    /**
     * Called by the keyboard when a field gains focus. A marked field opens its recording if none is
     * open. Any field without the marker pauses recording, so nothing outside the test is captured.
     */
    @JvmStatic
    fun onStartInputView(context: Context, info: EditorInfo?) {
        val id = idFrom(info?.privateImeOptions)
        if (id == null) {
            fieldMatches = false
            return
        }
        if (!recording || currentId != id) start(context, id)
        fieldMatches = true
        if (active) {
            emit(event("field_focus")
                .put("inputType", info?.inputType ?: 0)
                .put("imeOptions", info?.imeOptions ?: 0)
                .put("package", info?.packageName))
            scheduleLayout("field_focus")
        }
    }

    @JvmStatic
    fun onFinishInputView() {
        if (active) emit(event("field_blur"))
        fieldMatches = false
    }

    /** Starts an event line with its type and time. */
    @JvmStatic
    fun event(type: String): TypingTestJson =
        TypingTestJson().put("type", type).put("t", SystemClock.uptimeMillis())

    /** Queues a finished event for the writer thread. Safe to call from any thread. */
    @JvmStatic
    fun emit(json: TypingTestJson) {
        if (!recording) return
        val line = json.build()
        val ex = executor ?: return
        val w = writer ?: return
        try {
            // Flushed per line, on the writer thread. The simulator reads the file while the run is
            // going, and a crash then loses at most the line in flight.
            ex.execute { try { w.write(line); w.write("\n"); w.flush() } catch (_: Exception) {} }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Writes queued lines to disk now. The typing test calls this before it copies the file. */
    @JvmStatic
    fun flush() {
        val ex = executor ?: return
        val w = writer ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        ex.execute { try { w.flush() } catch (_: Exception) {}; done.countDown() }
        try { done.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    /**
     * Records a raw touch event, with every pointer and the batched move history.
     *
     * Positions are view-local, as the keyboard view received them. The `layout` event gives the
     * padding and vertical correction that map them to key coordinates.
     */
    @JvmStatic
    fun touch(e: MotionEvent) {
        if (!active) return
        if (layoutDirty && e.actionMasked == MotionEvent.ACTION_DOWN) {
            layoutDirty = false
            emitLayout("first_touch")
        }
        val n = e.pointerCount
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            ids[i] = e.getPointerId(i)
            xs[i] = e.getX(i)
            ys[i] = e.getY(i)
        }
        val ev = event("touch")
            .put("action", MotionEvent.actionToString(e.actionMasked))
            .put("index", e.actionIndex)
            .put("et", e.eventTime)
            .put("dt", e.downTime)
            .putInts("ids", ids)
            .putFloats("x", xs)
            .putFloats("y", ys)
        val h = e.historySize
        if (h > 0 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val ht = LongArray(h)
            val hx = FloatArray(h * n)
            val hy = FloatArray(h * n)
            for (k in 0 until h) {
                ht[k] = e.getHistoricalEventTime(k)
                for (i in 0 until n) {
                    hx[k * n + i] = e.getHistoricalX(i, k)
                    hy[k * n + i] = e.getHistoricalY(i, k)
                }
            }
            // Row-major: history step k, pointer i is at k * pointers + i.
            ev.putLongs("ht", ht).putFloats("hx", hx).putFloats("hy", hy)
        }
        emit(ev)
    }

    /**
     * Remembers the keyboard on screen, and records its key layout while recording. Called on every
     * keyboard change, active or not, so a recording that opens later can still write the layout.
     */
    @JvmStatic
    fun onKeyboardSet(view: View, keyboard: Keyboard?, verticalCorrection: Float) {
        lastKeyboardView = WeakReference(view)
        lastKeyboard = keyboard?.let { WeakReference(it) }
        lastPaddingLeft = view.paddingLeft
        lastPaddingTop = view.paddingTop
        lastVerticalCorrection = verticalCorrection
        layoutDirty = true
        if (active) scheduleLayout("keyboard_set")
    }

    /**
     * True after a keyboard change until a touch has written the layout. The first touch after a
     * change writes it again, because only then is the keyboard surely laid out and done animating.
     */
    @Volatile
    private var layoutDirty = true

    /**
     * Writes the layout now if the view is laid out, and again once the keyboard has settled. The
     * keyboard window slides in, so a position read during the slide is wrong. Readers take the
     * newest `layout` event with a non-zero `viewW`.
     */
    private fun scheduleLayout(reason: String) {
        val view = lastKeyboardView?.get() ?: return
        emitLayout(reason)
        view.postDelayed({ if (active) emitLayout("${reason}_settled") }, 700)
    }

    private fun emitLayout(reason: String) {
        val view = lastKeyboardView?.get() ?: return
        val keyboard = lastKeyboard?.get() ?: return
        if (view.width == 0) {
            view.post { if (active && view.width > 0) emitLayout("${reason}_posted") }
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val keys = StringBuilder("[")
        var first = true
        for (key in keyboard.sortedKeys) {
            if (!first) keys.append(',')
            first = false
            keys.append(TypingTestJson()
                .put("code", key.code)
                .put("label", key.label)
                .put("x", key.x).put("y", key.y)
                .put("w", key.width).put("h", key.height)
                .put("hx", key.hitBox.left).put("hy", key.hitBox.top)
                .put("hw", key.hitBox.width()).put("hh", key.hitBox.height())
                .build())
        }
        keys.append(']')
        emit(event("layout")
            .put("reason", reason)
            .put("screenX", loc[0]).put("screenY", loc[1])
            .put("viewW", view.width).put("viewH", view.height)
            .put("padLeft", lastPaddingLeft).put("padTop", lastPaddingTop)
            .put("verticalCorrection", lastVerticalCorrection)
            .put("kbW", keyboard.mOccupiedWidth).put("kbH", keyboard.mOccupiedHeight)
            .put("density", view.resources.displayMetrics.density)
            .putRaw("keys", keys.toString()))
    }

    /** Lists recordings, newest first. */
    @JvmStatic
    fun list(context: Context): List<File> =
        (File(context.filesDir, DIR).listFiles()?.toList() ?: emptyList())
            .filter { it.name.endsWith(".jsonl") }
            .sortedByDescending { it.lastModified() }
}
