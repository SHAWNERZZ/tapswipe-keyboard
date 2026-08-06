package org.futo.inputmethod.latin.tapswipe

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where this user's finger actually lands, per key.
 *
 * The decoder is layout-conditioned: `SwipeDecoder.setMode` takes per-key `cx`/`cy` arrays that
 * become the model's `layout_keys` input tensor. So the way to teach it someone's habits is to hand
 * it key positions shifted toward where they really type, rather than the layout's nominal centres.
 * This holds the evidence for that shift.
 *
 * Everything here is in **normalized layout space** - the same space as
 * [org.futo.inputmethod.latin.SwipeDecoderDictionary.appliedLayoutInfo]'s `xs`/`ys` and the
 * coordinates a [TapSwipeSession] stroke stores. Keeping one space end to end is deliberate: the
 * bugs in this area all come from mixing frames.
 *
 * Content-free by construction: per-key geometry and counts, no characters in sequence, no words,
 * nothing about *what* was typed. The only signal is which keys were used and how often.
 */
object TapSwipeTouchModel {
    const val TAG = "TapSwipeTouchModel"
    private const val FILE_NAME = "tapswipe-touch-model.json"

    /**
     * Wall-clock half-life for evidence, in days.
     *
     * Decay is applied on elapsed *time*, not on how many samples have arrived since. An
     * update-counted EMA never really forgets - a key you stopped using keeps whatever bias it had
     * forever, and a key you use constantly forgets in an afternoon. Typing habits drift, so old
     * geometry should fade on its own.
     */
    private const val HALF_LIFE_DAYS = 45.0
    private const val MS_PER_DAY = 24.0 * 60.0 * 60.0 * 1000.0

    /**
     * Effective weight at which a key is considered fully learned. Below it the applied shift is
     * scaled down, so a key ramps in rather than jumping the moment it sees one sample.
     */
    private const val CONFIDENCE_HALF_WEIGHT = 8.0f

    /**
     * Hard ceiling on the shift, as a fraction of key size. Beyond this we would be moving a key
     * far enough to sit under a neighbour, which is how a "helpful" model starts producing words
     * the user did not aim for.
     */
    const val MAX_SHIFT_FRACTION = 0.25f

    /**
     * Spread past which a key's evidence is treated as noise rather than a habit. If someone hits
     * a key all over the place there is no consistent offset to learn, and averaging scatter just
     * produces a confident-looking number with nothing behind it.
     */
    private const val MAX_USEFUL_SPREAD_FRACTION = 0.60f

    // ---------------------------------------------------------------- stored shape

    /**
     * Decay-weighted running moments for one key.
     *
     * Sums rather than a mean/variance pair so that decay is a single multiply across the whole
     * accumulator, and so weighted samples (a point we are less sure of contributes less) fall out
     * naturally.
     */
    @Serializable
    data class KeyAccum(
        var sumW: Float = 0f,
        var sumWdx: Float = 0f,
        var sumWdy: Float = 0f,
        var sumWdx2: Float = 0f,
        var sumWdy2: Float = 0f,
        var count: Int = 0,
        var lastUpdateMs: Long = 0L
    ) {
        val meanDx: Float get() = if (sumW > 0f) sumWdx / sumW else 0f
        val meanDy: Float get() = if (sumW > 0f) sumWdy / sumW else 0f

        val spreadX: Float get() = spread(sumWdx2, meanDx)
        val spreadY: Float get() = spread(sumWdy2, meanDy)

        private fun spread(sumSq: Float, mean: Float): Float {
            if (sumW <= 0f) return 0f
            val variance = (sumSq / sumW) - mean * mean
            return if (variance <= 0f) 0f else sqrt(variance)
        }

        /** 0..1, how much of the measured offset we are willing to actually apply. */
        val confidence: Float
            get() {
                if (sumW <= 0f) return 0f
                return sumW / (sumW + CONFIDENCE_HALF_WEIGHT)
            }
    }

    @Serializable
    data class LayoutModel(
        /** Key is the code point, as a string - JSON object keys cannot be ints. */
        val keys: MutableMap<String, KeyAccum> = mutableMapOf()
    )

    @Serializable
    data class ModelFile(
        val version: Int = 1,
        val layouts: MutableMap<String, LayoutModel> = mutableMapOf()
    )

    // ---------------------------------------------------------------- state

    private val lock = Any()
    private var model = ModelFile()
    private var loaded = false
    private var dirty = false

    /** Bumped on every change, so the UI can recompose without polling the file. */
    @Volatile
    var revision: Int = 0
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Identifies which layout evidence belongs to. Letters rather than a locale name because that
     * is what actually determines key positions - two locales sharing QWERTY should share evidence,
     * and a Dvorak switch must not inherit QWERTY's offsets. Orientation is folded in because the
     * same layout has different ergonomics landscape vs portrait.
     */
    @JvmStatic
    fun layoutKey(letters: String, isLandscape: Boolean): String =
        (if (isLandscape) "L:" else "P:") + letters

    // ---------------------------------------------------------------- persistence

    /**
     * Reads the model from disk if it has not been read yet. Idempotent, so every entry point that
     * touches the model can call it without coordinating.
     *
     * That matters more than it looks. The keyboard service and the settings UI live in the same
     * process, but either can start it: opening settings without having used the keyboard leaves
     * the model unread, and anything reading it then sees an empty model that does not reflect
     * disk. Making load cheap and idempotent means no caller has to know which component won the
     * race.
     */
    @JvmStatic
    fun ensureLoaded(context: Context) = ensureLoaded(context.filesDir)

    /**
     * Directory-based rather than Context-based: nothing here needs an Android Context, only
     * somewhere to put a file. Taking the directory keeps the persistence rules - above all the
     * refusal in [save] - reachable from plain JVM tests.
     */
    @JvmStatic
    fun ensureLoaded(dir: File) {
        synchronized(lock) {
            if (loaded) return
            val file = File(dir, FILE_NAME)
            try {
                // readFully() rather than a File.exists() check: AtomicFile keeps a .bak from an
                // interrupted write, and only readFully() knows to recover from it. Testing
                // exists() on the main path would silently discard a recoverable model.
                val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
                model = json.decodeFromString(ModelFile.serializer(), text)
                revision++
                if (DEBUG_PERSIST) {
                    Log.d(TAG, "loaded touch model: ${totalSamplesLocked()} samples")
                }
            } catch (e: java.io.FileNotFoundException) {
                // Nothing saved yet - a normal first run, not a problem.
                model = ModelFile()
            } catch (e: Throwable) {
                // A corrupt model is not worth taking the keyboard down for - start over.
                Log.e(TAG, "could not read touch model, starting fresh", e)
                model = ModelFile()
            }
            loaded = true
        }
    }

    /** Kept for the existing call site in LatinIME. */
    @JvmStatic
    fun load(context: Context) = ensureLoaded(context.filesDir)

    /**
     * Discards what is in memory and re-reads from disk. For a settings restore, which replaces the
     * file underneath a process that has already read it.
     */
    @JvmStatic
    fun reloadFromDisk(context: Context) {
        synchronized(lock) { loaded = false }
        ensureLoaded(context.filesDir)
    }

    private const val DEBUG_PERSIST = true

    /** Writes synchronously; callers are expected to be off the main thread. */
    @JvmStatic
    fun save(context: Context) = save(context.filesDir)

    @JvmStatic
    fun save(dir: File) {
        val text: String
        synchronized(lock) {
            // Never write a model that was never read. Whatever is in memory would be a blank
            // model rather than the user's, and writing it would destroy weeks of learning that
            // is sitting intact on disk. This is the single most damaging thing this class could
            // do, so it is refused outright rather than guarded at each call site.
            if (!loaded) {
                Log.w(TAG, "refusing to save a touch model that was never loaded")
                return
            }
            if (!dirty) return
            text = json.encodeToString(model)
            dirty = false
            saveAttempts++
        }
        val atomic = AtomicFile(File(dir, FILE_NAME))
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            Log.e(TAG, "could not write touch model", e)
            if (out != null) atomic.failWrite(out)
            synchronized(lock) { dirty = true }
        }
    }

    @JvmStatic
    fun reset() {
        synchronized(lock) {
            model = ModelFile()
            dirty = true
            revision++
        }
    }

    // ---------------------------------------------------------------- recording

    /**
     * Folds one observation into a key's accumulator.
     *
     * @param dx,dy offset from the key's nominal centre, in normalized layout space
     * @param weight how much to trust this sample (see [TapSwipeStrokeAligner])
     */
    @JvmStatic
    fun record(
        layoutKey: String, codePoint: Int, dx: Float, dy: Float, weight: Float, nowMs: Long
    ) {
        if (weight <= 0f || !dx.isFinite() || !dy.isFinite()) return
        synchronized(lock) {
            val layout = model.layouts.getOrPut(layoutKey) { LayoutModel() }
            val accum = layout.keys.getOrPut(codePoint.toString()) { KeyAccum() }

            decayInPlace(accum, nowMs)

            accum.sumW += weight
            accum.sumWdx += weight * dx
            accum.sumWdy += weight * dy
            accum.sumWdx2 += weight * dx * dx
            accum.sumWdy2 += weight * dy * dy
            accum.count += 1
            accum.lastUpdateMs = nowMs
            dirty = true
        }
        revision++
    }

    /**
     * Removes a sample previously passed to [record].
     *
     * Used when a word is corrected moments after being learned from: the geometry recorded for it
     * described a word the user did not mean, so leaving it in would teach the wrong lesson from a
     * gesture we now know was misread.
     *
     * Exact reversal relies on decay being negligible over the seconds between learning a word and
     * rejecting it - [decayInPlace] scales by elapsed wall-clock time, which over that interval is
     * indistinguishable from 1. Sums are clamped at zero regardless, since float arithmetic that
     * drifted negative would produce a nonsense mean.
     */
    @JvmStatic
    fun retract(
        layoutKey: String, codePoint: Int, dx: Float, dy: Float, weight: Float, nowMs: Long
    ) {
        if (weight <= 0f || !dx.isFinite() || !dy.isFinite()) return
        synchronized(lock) {
            val accum = model.layouts[layoutKey]?.keys?.get(codePoint.toString()) ?: return
            decayInPlace(accum, nowMs)

            accum.sumW = max(0f, accum.sumW - weight)
            accum.sumWdx -= weight * dx
            accum.sumWdy -= weight * dy
            accum.sumWdx2 = max(0f, accum.sumWdx2 - weight * dx * dx)
            accum.sumWdy2 = max(0f, accum.sumWdy2 - weight * dy * dy)
            accum.count = max(0, accum.count - 1)

            // Nothing left worth keeping: drop the entry so it reads as unseen rather than as a
            // key with a confident-looking zero.
            if (accum.sumW <= 1e-6f) {
                model.layouts[layoutKey]?.keys?.remove(codePoint.toString())
            }
            dirty = true
        }
        revision++
    }

    /**
     * Applies wall-clock decay up to [nowMs]. Called on read as well as write, so a key that has
     * not been touched in months reports faded evidence rather than whatever it held when last
     * written.
     */
    private fun decayInPlace(accum: KeyAccum, nowMs: Long) {
        if (accum.lastUpdateMs <= 0L || accum.sumW <= 0f) return
        val elapsedMs = nowMs - accum.lastUpdateMs
        if (elapsedMs <= 0L) return
        val halfLives = (elapsedMs / MS_PER_DAY) / HALF_LIFE_DAYS
        val factor = exp(-halfLives * 0.6931472).toFloat() // ln 2
        if (factor >= 0.9999f) return
        accum.sumW *= factor
        accum.sumWdx *= factor
        accum.sumWdy *= factor
        accum.sumWdx2 *= factor
        accum.sumWdy2 *= factor
        accum.lastUpdateMs = nowMs
    }

    // ---------------------------------------------------------------- applying

    /**
     * The shift to apply to a key's nominal position, or null when there is not enough to say.
     *
     * Three things gate it, all of which exist to keep a half-learned key from moving somewhere the
     * user did not ask for: confidence ramps the magnitude in with evidence, scatter suppresses
     * keys with no consistent habit, and the result is clamped to [MAX_SHIFT_FRACTION] of key size.
     *
     * @param halfW,halfH half key size in normalized space, for the cap and the scatter test
     */
    @JvmStatic
    fun shiftFor(
        layoutKey: String, codePoint: Int, halfW: Float, halfH: Float, nowMs: Long
    ): FloatArray? {
        synchronized(lock) {
            val accum = model.layouts[layoutKey]?.keys?.get(codePoint.toString()) ?: return null
            decayInPlace(accum, nowMs)
            if (accum.sumW <= 0f) return null

            val conf = accum.confidence
            if (conf <= 0.02f) return null

            // Scatter check, per axis: a key can be consistent horizontally and wild vertically.
            val scatterX = scatterScale(accum.spreadX, halfW * 2f)
            val scatterY = scatterScale(accum.spreadY, halfH * 2f)
            if (scatterX <= 0f && scatterY <= 0f) return null

            val capX = halfW * 2f * MAX_SHIFT_FRACTION
            val capY = halfH * 2f * MAX_SHIFT_FRACTION
            val dx = clamp(accum.meanDx * conf * scatterX, -capX, capX)
            val dy = clamp(accum.meanDy * conf * scatterY, -capY, capY)
            if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return null
            return floatArrayOf(dx, dy)
        }
    }

    /** 1 when tightly clustered, tapering to 0 as spread approaches [MAX_USEFUL_SPREAD_FRACTION]. */
    private fun scatterScale(spread: Float, keySize: Float): Float {
        if (keySize <= 0f) return 0f
        val limit = keySize * MAX_USEFUL_SPREAD_FRACTION
        if (spread >= limit) return 0f
        return 1f - (spread / limit)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    // ---------------------------------------------------------------- inspection

    /** One key's learned state, for the visualization. */
    data class KeyStats(
        val codePoint: Int,
        val meanDx: Float,
        val meanDy: Float,
        val spreadX: Float,
        val spreadY: Float,
        val confidence: Float,
        val count: Int,
        val appliedDx: Float,
        val appliedDy: Float,
        val lastUpdateMs: Long
    )

    /**
     * Everything known about one layout, with both the raw measurement and the shift actually
     * applied. Showing both matters: the gap between them is the safety machinery doing its job,
     * and without it a capped or scatter-suppressed key looks identical to one with no data.
     */
    @JvmStatic
    fun statsFor(layoutKey: String, halfW: Float, halfH: Float, nowMs: Long): List<KeyStats> {
        synchronized(lock) {
            val layout = model.layouts[layoutKey] ?: return emptyList()
            return layout.keys.mapNotNull { (cpStr, accum) ->
                val cp = cpStr.toIntOrNull() ?: return@mapNotNull null
                decayInPlace(accum, nowMs)
                if (accum.sumW <= 0f) return@mapNotNull null

                val conf = accum.confidence
                val scatterX = scatterScale(accum.spreadX, halfW * 2f)
                val scatterY = scatterScale(accum.spreadY, halfH * 2f)
                val capX = halfW * 2f * MAX_SHIFT_FRACTION
                val capY = halfH * 2f * MAX_SHIFT_FRACTION

                KeyStats(
                    codePoint = cp,
                    meanDx = accum.meanDx,
                    meanDy = accum.meanDy,
                    spreadX = accum.spreadX,
                    spreadY = accum.spreadY,
                    confidence = conf,
                    count = accum.count,
                    appliedDx = clamp(accum.meanDx * conf * scatterX, -capX, capX),
                    appliedDy = clamp(accum.meanDy * conf * scatterY, -capY, capY),
                    lastUpdateMs = accum.lastUpdateMs
                )
            }.sortedBy { it.codePoint }
        }
    }

    /** Total samples across every layout - for "is this thing even learning" at a glance. */
    @JvmStatic
    fun totalSamples(): Int = synchronized(lock) { totalSamplesLocked() }

    private fun totalSamplesLocked(): Int =
        model.layouts.values.sumOf { layout -> layout.keys.values.sumOf { it.count } }

    /** True once the model has been read from disk. */
    @JvmStatic
    fun isLoaded(): Boolean = synchronized(lock) { loaded }

    /** True when there are unsaved changes. */
    @JvmStatic
    fun isDirty(): Boolean = synchronized(lock) { dirty }

    /**
     * Times a save got past the guards and serialized the model.
     *
     * The obvious observable - [isDirty] - cannot serve, because a failed write deliberately marks
     * the model dirty again so the next attempt retries. This counts intent rather than outcome,
     * which is what distinguishes "refused to save" from "tried and the disk said no".
     */
    @Volatile
    @JvmStatic
    var saveAttempts: Int = 0
        private set

    /**
     * Clears everything including the loaded flag.
     *
     * Test-only. Production must never clear `loaded` on its own: a reset leaves an intentionally
     * empty model that still has to be written, and forgetting it was loaded would make [save]
     * refuse to persist the user's reset.
     */
    @JvmStatic
    fun resetForTests() {
        synchronized(lock) {
            model = ModelFile()
            dirty = false
            loaded = false
            saveAttempts = 0
            revision++
        }
    }

    @JvmStatic
    fun knownLayoutKeys(): List<String> = synchronized(lock) { model.layouts.keys.sorted() }
}
