package org.futo.inputmethod.latin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.tapswipe.EnterFlicks
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.internal.isAlphabet
import org.futo.inputmethod.latin.common.ComposedData
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.tapswipe.TapSwipeTouchModel
import org.futo.inputmethod.latin.tapswipe.TapSwipeDecodeInput
import org.futo.inputmethod.latin.tapswipe.TapSwipeSegmenter
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValues
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.ml.inference.SwipeDecoder
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ENCODER_ASSET = "futo-swipe/honorable_sturgeon/model_fp32.pte"
// TapSwipe keeps FUTO's previous context model (2026-11-12). Upstream moved to chosen_planarian, but
// the prebuilt swipe library has its scoring weights compiled in and knows only hungry_jellyfish. With
// chosen_planarian it logs "Failed to find scoring" and decodes with no language model at all. The
// futo-swipe submodule stays at 9393187 until FUTO ships a library that knows the new model.
private const val ENGLISH_LM_ASSET = "futo-swipe/hungry_jellyfish/context_lm.pte"
private const val ENGLISH_LM_VOCAB_ASSET = "futo-swipe/hungry_jellyfish/vocab.txt"
private const val ENGLISH_DECODER_ASSET = "futo-swipe/magic_macaw/model_fp32.pte"
private const val SCORING_ASSET = "futo-swipe/scoring.json"

/** How many next-word predictions a tapped word is looked up in. Not found means not a likely word. */
private const val TAPPED_WORD_PREDICTIONS = 400

/** How far below the least likely prediction an unexpected common short word scores. */
private const val UNEXPECTED_TAPPED_WORD = 1f

@Serializable
data class Input(
    val x: Float,
    val y: Float,
    val t: Float
)

@Serializable
data class Inputs(val data: List<Input>)

internal fun getKeyXY(key: Key, keyboard: Keyboard): Pair<Float, Float> {
    var xMid = key.drawX + key.drawWidth/2.0f
    var yMid = key.y + key.verticalGap/2.0f + key.height/2.0f

    xMid /= keyboard.mBaseWidth
    yMid *= (1.0f / (keyboard.mBaseHeight - keyboard.mPadding.bottom)) * (4.0f / 3.0f)

    return xMid to yMid
}

internal fun getKeyYBottom(key: Key, keyboard: Keyboard): Float =
    (key.y + key.verticalGap + key.height) *
            ((1.0f / (keyboard.mBaseHeight - keyboard.mPadding.bottom)) * (4.0f / 3.0f))


val Key.swipeCode: Int get() = swipeCodeOverride ?: code

data class LayoutInfoForModel(
    val letters: String,
    val xs: List<Float>,
    val ys: List<Float>,
    val decoder: String,
    val lm: String,
    val sx: Float, val sy: Float,
    val ox: Float, val oy: Float,
) {
    companion object {
        val DEFAULT = LayoutInfoForModel(
            letters = "",
            xs = emptyList(),
            ys = emptyList(),
            decoder = "",
            lm = "",
            sx = 1.0f, sy = 1.0f,
            ox = 0.0f, oy = 0.0f
        )

        @JvmStatic
        fun buildLayoutInfo(context: Context, keyboard: Keyboard, settingsValues: SettingsValues): LayoutInfoForModel? =
            (context.getSetting(SwipeSpecialDecoderSetting).let {
                if(it) SpecialDecoder.matchLayout(keyboard, settingsValues)
                else null
            } ?: run {
                val keys = keyboard.sortedKeys
                    .filter { settingsValues.isWordCodePoint(it.swipeCode) && !Character.isDigit(it.swipeCode) }
                    .sortedBy { it.swipeCode }
                    .distinctBy { it.swipeCode } // The engine currently can't handle letters existing multiple times. Sorry custom layouts!
                val letters = keys.joinToString(separator="") { Character.toString(Character.toLowerCase(it.swipeCode)) }

                // Guard against non-alphabet keyboards
                if(!keyboard.mId.mElement.kind.isAlphabet || letters.length < 6) return@run null

                val positions = keys.map { getKeyXY(it, keyboard) }

                val yScale = 1.0f / (keys.maxOf { getKeyYBottom(it, keyboard) }.coerceAtLeast(1.0f))

                val xs = positions.map { it.first }
                val ys = positions.map { it.second * yScale }

                return@run LayoutInfoForModel(
                    letters = letters,
                    xs = xs, ys = ys,
                    sx = 1.0f, sy = yScale,
                    ox = 0.0f, oy = 0.0f,
                    decoder = "",
                    lm = SpecialContextLM.match(settingsValues),
                )
            }).let {
                if(!context.getSetting(SwipeLanguageModelSetting)) {
                    it?.copy(lm="")
                } else {
                    it
                }
            }
    }
}

private class SpecialContextLM private constructor(
    val language: String,
    val asset: String
) {
    companion object {
        private val contextLMs = listOf(
            SpecialContextLM("en", ENGLISH_LM_ASSET)
        )

        fun match(settingsValues: SettingsValues): String {
            if(settingsValues.mMultilingualLocales.isNotEmpty()) return ""

            return contextLMs.firstOrNull { it.language == settingsValues.mLocale.language }?.asset ?: ""
        }
    }
}

private class SpecialDecoder private constructor(
    val asset: String,

    val language: String,
    val layoutLetters: String,

    // These are normalized such that the lowest value is always 0, and highest value is always 1
    val layoutXs: List<Float>,
    val layoutYs: List<Float>,

    val xOffset: Float,
    val xScale: Float,
    val yOffset: Float,
    val yScale: Float
) {
    val maxXDeviation = xScale * 0.1
    val maxYDeviation = yScale * 0.1

    companion object {
        private val specialDecoders = listOf(
            SpecialDecoder(
                asset = ENGLISH_DECODER_ASSET,
                language = "en",
                layoutLetters = "abcdefghijklmnopqrstuvwxyz",
                layoutXs = listOf(0.055555555555555566f, 0.611111111111111f, 0.38888888888888895f, 0.2777777777777778f, 0.22222222222222227f, 0.38888888888888895f, 0.5000000000000001f, 0.611111111111111f, 0.7777777777777778f, 0.7222222222222222f, 0.8333333333333334f, 0.9444444444444445f, 0.8333333333333334f, 0.7222222222222222f, 0.888888888888889f, 1.0f, 0.0f, 0.33333333333333337f, 0.1666666666666667f, 0.44444444444444453f, 0.6666666666666667f, 0.5000000000000001f, 0.11111111111111112f, 0.2777777777777778f, 0.5555555555555556f, 0.1666666666666667f),
                layoutYs = listOf(0.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.5f, 0.5f, 0.5f, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f),
                xOffset = 0.05f,
                xScale = 0.8999999999999999f,
                yOffset = 0.1667f,
                yScale = 0.6666000000000001f,
            )
        )

        internal fun matchLayout(keyboard: Keyboard, settingsValues: SettingsValues): LayoutInfoForModel? {
            if(settingsValues.mMultilingualLocales.isNotEmpty()) return null

            val keys = keyboard.sortedKeys.associate { Character.toLowerCase(it.swipeCode) to it }

            return specialDecoders.firstNotNullOfOrNull { decoder ->
                if(keyboard.mId.mLocale.language != decoder.language) return@firstNotNullOfOrNull null

                // In case the layout has multiple repeated instances of the same letter, let's forget
                // about using a special decoder, since this should never occur in a regular layout.
                if(decoder.layoutLetters.any { letter -> keyboard.sortedKeys.count { key ->
                        Character.toLowerCase(key.swipeCode) == letter.code
                } > 1}) return@firstNotNullOfOrNull null

                // Make sure the letters are a perfect match!
                val expectedLayoutLetters = decoder.layoutLetters.map { it.code }.toSortedSet()
                val ourLayoutLetters = keys.keys.filter { !Character.isDigit(it) && settingsValues.isWordCodePoint(it) }.toSortedSet()
                if(expectedLayoutLetters != ourLayoutLetters) return@firstNotNullOfOrNull null

                val relevantKeysN = decoder.layoutLetters.map { keys[it.code] }
                if(relevantKeysN.any { it == null }) return@firstNotNullOfOrNull null
                val relevantKeys = relevantKeysN.filterNotNull()
                val positionsNotNormalized = relevantKeys.map { getKeyXY(it, keyboard) }

                val minX = positionsNotNormalized.minOf { it.first }
                val minY = positionsNotNormalized.minOf { it.second }

                val maxX = positionsNotNormalized.maxOf { it.first }
                val maxY = positionsNotNormalized.maxOf { it.second }

                val offsetX = minX
                val offsetY = minY
                val scaleX = maxX - offsetX
                val scaleY = maxY - offsetY

                if(scaleX == 0.0f || scaleY == 0.0f) return@firstNotNullOfOrNull null

                val normX = positionsNotNormalized.map { (it.first - offsetX) / scaleX }
                val normY = positionsNotNormalized.map { (it.second - offsetY) / scaleY }

                val matches = decoder.layoutXs.zip(normX).all { (a, b) -> abs(b - a) < decoder.maxXDeviation }
                           && decoder.layoutYs.zip(normY).all { (a, b) -> abs(b - a) < decoder.maxYDeviation }

                if(!matches) return@firstNotNullOfOrNull null

                val dsx = decoder.xScale / scaleX
                val dsy = decoder.yScale / scaleY
                val dox = decoder.xOffset - offsetX * dsx
                val doy = decoder.yOffset - offsetY * dsy
                LayoutInfoForModel(
                    decoder = decoder.asset,
                    letters = decoder.layoutLetters,
                    xs = decoder.layoutXs.map { it * decoder.xScale + decoder.xOffset },
                    ys = decoder.layoutYs.map { it * decoder.yScale + decoder.yOffset },
                    sx = dsx, sy = dsy, ox = dox, oy = doy,
                    lm = SpecialContextLM.match(settingsValues),
                )
            }
        }
    }
}

/** Raw-pixel to layout-space coordinate transforms; see [SwipeDecoderDictionary.currentNormalizers]. */
class TapSwipeNormalizers(
    val x: (Float) -> Float,
    val y: (Float) -> Float
)

val LegacySwipeSetting = SettingsKey(booleanPreferencesKey("swipe_mode_legacy"), false)

/**
 * Master switch for the TapSwipe input model (accumulate strokes across finger lifts, finalize
 * only on space/punctuation/Enter). Off by default so stock behaviour is one toggle away for
 * A/B comparison on the same build.
 */
val TapSwipeModeSetting = SettingsKey(booleanPreferencesKey("tapswipe_mode"), false)

/**
 * Median gap between taps, in milliseconds, at or above which a swipe-free word counts as
 * deliberately pecked rather than fluently typed. Below it the word is treated as ordinary typing
 * (legacy tap mode) and keeps autocorrect.
 */
val TapSwipePeckCadenceSetting = SettingsKey(intPreferencesKey("tapswipe_peck_cadence_ms"), 250)

/**
 * How many quick taps in a row - counted across words, reset by any swipe - before legacy tap mode
 * turns on. Legacy tap shows letters and restores ordinary autocorrect, so someone who picks the
 * keyboard up and just starts typing gets a normal keyboard without having to discover anything.
 */
val TapSwipeLegacyTapRunSetting = SettingsKey(intPreferencesKey("tapswipe_legacy_tap_run"), 5)

/**
 * Adaptive key geometry: feed the decoder key positions shifted toward where this user actually
 * swipes, instead of the layout's nominal centres.
 *
 * Off by default. Unlike the other TapSwipe options this one accumulates state across sessions, so
 * a bad interaction would follow the user around rather than ending with the current word - it
 * earns an explicit opt-in.
 */
val TapSwipeAdaptiveGeometrySetting =
    SettingsKey(booleanPreferencesKey("tapswipe_adaptive_geometry"), false)

/**
 * Nintype-style whole-stroke gesture shortcuts.
 *
 * One flag covering both halves on purpose, for now: the gesture that produces a comma, and the
 * removal of the comma key it replaces. Splitting them would mean shipping a layout with a dead key
 * on it, or a gesture competing with the key it was meant to supersede.
 */
val TapSwipeNintypeGesturesSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_nintype_gestures"), false)

/**
 * Adds a thin apostrophe key in the gap to the right of L, as on the original Nintype keyboard.
 *
 * Swaps stock `qwerty` for `qwerty_nintype` when the keyboard is built and measured. See
 * [org.futo.inputmethod.latin.tapswipe.TapSwipeLayouts]. Other layouts are left alone.
 */
val TapSwipeApostropheKeySetting =
    SettingsKey(booleanPreferencesKey("tapswipe_apostrophe_key"), false)

/** Set once [org.futo.inputmethod.latin.tapswipe.TapSwipeLayouts.migrate] has run. */
val TapSwipeApostropheKeyMigratedSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_apostrophe_key_migrated"), false)

/**
 * Lets one word break into two when a tapped word sits beside a swiped one.
 *
 * A word normally runs from one finalizer to the next, so tapping `i` after swiping `hello` folds
 * the tap into that word. This asks, on every stroke, whether the evidence reads better as two
 * words, and inserts the space itself when it does. See `docs/plans/WORD_SPLIT.md`.
 *
 * Off by default. It commits text on its own judgement, which nothing else in this fork does.
 */
val TapSwipeAutoSplitSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_auto_split"), false)

/** What one tap of the backspace key removes. See [TapSwipeBackspaceTapSetting]. */
object BackspaceTap {
    /** Removes the last gesture of the last word, then whole words for older text. */
    const val LAST_GESTURE = 0

    /** Removes a whole word every time. */
    const val WHOLE_WORD = 1

    /** Stock behavior. Removes one character. */
    const val ONE_CHARACTER = 2
}

/**
 * What one tap of the backspace key removes.
 *
 * Replaces an earlier on/off setting for whole-word delete. The three behaviors are mutually
 * exclusive, so one choice states that where a pair of switches would hide it.
 *
 * [BackspaceTap.LAST_GESTURE] is the default, and is the reason the gesture trails exist: removing
 * one gesture at a time is only usable when the gestures are visible.
 */
val TapSwipeBackspaceTapSetting =
    SettingsKey(intPreferencesKey("tapswipe_backspace_tap"), BackspaceTap.LAST_GESTURE)

/**
 * Keeps every gesture of the word being typed drawn on the keys, newest brightest.
 *
 * Separate from the stock gesture trail, which fades a second after a finger lifts. This one lasts
 * for the word, so a backspace that removes one gesture has something to aim at.
 */
val TapSwipeWordTrailsSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_word_trails"), false)

/**
 * An upward swipe on the delete key removes the last word.
 *
 * Independent of the upstream swipe-backspace setting, which governs the horizontal slide. Turning
 * the slide off does not turn this off, because they are separate gestures on the same key.
 */
val TapSwipeBackspaceSwipeUpSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_backspace_swipe_up"), false)

/**
 * Punctuation and actions on swipes off the enter key.
 *
 * Separate from [TapSwipeNintypeGesturesSetting], unlike the comma gesture and its key removal:
 * those two are halves of one change, where this is a self-contained addition to a key that is not
 * otherwise involved in typing words.
 */
val TapSwipeEnterFlicksSetting =
    SettingsKey(booleanPreferencesKey("tapswipe_enter_flicks"), false)

/**
 * Which direction off the enter key does what, encoded by [org.futo.inputmethod.latin.tapswipe.EnterFlicks].
 *
 * Stored as one string rather than eight settings so the assignment is written and read as a whole.
 * Eight independent keys would let a partial write leave the enter key in a state the user never
 * chose, and would make "reset to defaults" eight operations that can half-fail.
 */
val TapSwipeEnterFlickMapSetting =
    SettingsKey(stringPreferencesKey("tapswipe_enter_flick_map"),
        EnterFlicks.serialize(EnterFlicks.DEFAULTS))

/**
 * Drops the period key, widening the enter key into the space it leaves.
 *
 * Gated on the enter flicks being on: without them the enter key gains width for nothing, and the
 * period becomes reachable only through the symbols page or double-space.
 */
val TapSwipeHidePeriodKeySetting =
    SettingsKey(booleanPreferencesKey("tapswipe_hide_period_key"), false)

/**
 * Shows typing speed on the space bar, measured per text field.
 *
 * Off by default - it is a curiosity rather than something that improves typing, and the space bar
 * is more useful showing nothing than showing a number nobody asked for.
 */
val TapSwipeWpmSetting = SettingsKey(booleanPreferencesKey("tapswipe_wpm"), false)

/**
 * Master Mode: letter keys render as dots instead of letters. Named after the equivalent mode in
 * the original Nintype keyboard. Peck mode temporarily reveals the letters again.
 */
val TapSwipeMasterModeSetting = SettingsKey(booleanPreferencesKey("tapswipe_master_mode"), false)


/**
 * How eagerly a finger movement is classified as a swipe rather than a tap.
 *
 * 1.0 is stock behaviour; the default here is 3.0, which testing found works well for the short
 * swipes the TapSwipe model encourages. Higher values make short swipes (e.g. ending a word by swiping `e` to
 * `r`) register sooner, at the cost of ordinary taps occasionally being read as gestures.
 * Applied uniformly to the three quantities that gate gesture recognition in
 * `GestureStrokeRecognitionPoints`: the fast-move speed test, and the dynamic distance and time
 * thresholds. The existing coarse "increase sensitivity" toggle multiplies on top of this.
 */
val SwipeSensitivitySetting = SettingsKey(floatPreferencesKey("swipe_sensitivity"), 3.0f)

/** Effective sensitivity multiplier, combining the slider with the legacy coarse toggle. */
fun currentSwipeSensitivity(coarseToggleOn: Boolean): Float {
    val s = DataStoreHelper.getSetting(SwipeSensitivitySetting)
    val clamped = if (s.isNaN() || s <= 0.0f) 1.0f else s.coerceIn(0.25f, 8.0f)
    return if (coarseToggleOn) clamped * 2.0f else clamped
}
val DisplayTop4Setting = SettingsKey(booleanPreferencesKey("swipe_use_top4_suggestions"), true)

val SwipeSpecialDecoderSetting = SettingsKey(booleanPreferencesKey("__experimental_swipe_special_decoder"), true)
val SwipeLanguageModelSetting = SettingsKey(booleanPreferencesKey("__experimental_swipe_language_model"), true)

@Serializable
data class SwipePoiSer(
    val x: Float, val y: Float, val t: Int
)
@Serializable
data class SwipeSegSer(val data: List<SwipePoiSer>)

class SwipeDecoderDictionary(val context: Context, val locale: Locale) : Dictionary("swipe", locale) {
    companion object {
        const val SWIPE_MODEL = ENCODER_ASSET

        var debugLogUntil: Long = 0L

        private var prevKeyboard: Keyboard? = null
        var appliedLayoutInfo: LayoutInfoForModel = LayoutInfoForModel.DEFAULT
            private set

        // for debug info
        val appliedScoring = mutableStateOf(SwipeDecoder.Scoring(0.0f, 0.0f, 0.0f, 0.0f))
        var appliedTries: LongArray? = null
        var appliedTrieWeights by mutableStateOf<FloatArray>(FloatArray(0))

        fun metadataFor(pteAsset: String): String
            = pteAsset.substringBeforeLast('/') + "/metadata.json"

        fun vocabFor(pteAsset: String): String {
            if(pteAsset.isEmpty()) return ""
            return pteAsset.substringBeforeLast('/') + "/vocab.txt"
        }

        @Serializable private data class ModelMetadata(val codename: String)
        private val CodenameParseJson = Json { ignoreUnknownKeys = true }
        fun parseMetadataToGetCodename(content: String): String =
            CodenameParseJson.decodeFromString<ModelMetadata>(content).codename

        private val createdFiles = mutableSetOf<String>()
        fun getFilePath(context: Context, assetName: String): String {
            if(assetName.isEmpty()) return ""

            val assets = context.assets
            val tmpDir = context.codeCacheDir
            val modelFile = File(tmpDir, assetName)

            if (modelFile.exists()) {
                if(assetName in createdFiles) return modelFile.absolutePath

                modelFile.delete()
            }
            modelFile.parentFile?.mkdirs()
            assets.open(assetName).use { inputStream ->
                modelFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            createdFiles.add(assetName)

            if(assetName.endsWith(".pte")) {
                // Ensure metadata is also present
                getFilePath(context, metadataFor(assetName))
            }

            return modelFile.absolutePath
        }


        @JvmStatic
        fun updateKeyboard(keyboard: Keyboard) {
            prevKeyboard = keyboard
        }

        /**
         * Coordinate normalizers mapping raw touch pixels into the layout space the decoder
         * expects. Must stay identical to the arithmetic in [transformSegment], or the TapSwipe
         * session's stored coordinates will not agree with the applied layout's key centers.
         *
         * Returns null when no keyboard is known yet.
         */
        @JvmStatic
        fun currentNormalizers(): TapSwipeNormalizers? {
            val kb = prevKeyboard ?: return null
            val w = kb.mBaseWidth.toFloat()
            val h = (kb.mBaseHeight - kb.mPadding.bottom).toFloat()
            if (w <= 0f || h <= 0f) return null
            val info = appliedLayoutInfo
            return TapSwipeNormalizers(
                { rawX -> rawX / w * info.sx + info.ox },
                { rawY -> minOf(1.0f, (rawY / h) * (4.0f / 3.0f) * info.sy + info.oy) }
            )
        }

        /**
         * Normalized `[x, y]` of a letter's key centre in the applied layout, or null if the
         * layout has no such letter.
         *
         * Taps are located this way rather than from their touch coordinates on purpose: the tap
         * and gesture paths are in *different* coordinate spaces (tap coordinates pass through
         * `MainKeyboardView.getKeyX/getKeyY`, which strips view padding; gesture points do not),
         * and `onCodeInput` reports `NOT_A_COORDINATE` unless the key has proximity correction.
         * The layout's own key centres are already in the decoder's space, so they sidestep both
         * problems. Phase 0 / S4 showed a single point is sufficient - dwell length has no effect,
         * since every segment is resampled to a fixed 64 points internally.
         */
        /**
         * Letters whose key centre is within about one key of a normalized point, nearest first
         * included. Used by the soft order across streams to know which letters a stroke could have
         * started or ended on.
         */
        @JvmStatic
        fun nearLetters(x: Float, y: Float): Set<Char> {
            val info = appliedLayoutInfo
            val half = normalizedKeyHalfExtent() ?: return emptySet()
            val kw = half[0] * 2f
            val kh = half[1] * 2f
            if (kw <= 0f || kh <= 0f) return emptySet()
            var best = Float.MAX_VALUE
            val d = FloatArray(info.letters.length) { i ->
                if (i >= info.xs.size || i >= info.ys.size) Float.MAX_VALUE
                else Math.hypot(((info.xs[i] - x) / kw).toDouble(), ((info.ys[i] - y) / kh).toDouble()).toFloat()
                    .also { if (it < best) best = it }
            }
            val out = HashSet<Char>()
            for (i in d.indices) if (d[i] <= maxOf(best + 0.01f, 1.0f)) out.add(info.letters[i])
            return out
        }

        /** Key centres and size for the soft order across streams, or null before a layout applies. */
        @JvmStatic
        fun timingGeometry(): org.futo.inputmethod.latin.tapswipe.TapSwipeTimingOrder.Geometry? {
            val half = normalizedKeyHalfExtent() ?: return null
            if (half[0] <= 0f || half[1] <= 0f) return null
            return org.futo.inputmethod.latin.tapswipe.TapSwipeTimingOrder.Geometry(
                { c -> normalizedKeyPosition(c.code) }, half[0] * 2f, half[1] * 2f)
        }

        /** Letter key width in normalized space, or 0 when no layout is applied. */
        @JvmStatic
        fun normalizedKeyWidth(): Float = normalizedKeyHalfExtent()?.let { it[0] * 2f } ?: 0f

        @JvmStatic
        fun normalizedKeyPosition(codePoint: Int): FloatArray? {
            val info = appliedLayoutInfo
            val idx = info.letters.indexOf(Character.toLowerCase(codePoint).toChar())
            if (idx < 0 || idx >= info.xs.size || idx >= info.ys.size) return null
            return floatArrayOf(info.xs[idx], info.ys[idx])
        }

        /**
         * Normalized `[x, y]` of an actual touch, or null if the layout is not ready or the
         * coordinates are absent.
         *
         * Takes raw coordinates in the *keyboard* frame - which is what reaches
         * `InputLogic.onCodeInput` after `getKeyX`/`getKeyY` strip the view padding - and puts them
         * through the same normalizers the gesture path uses, so a tap and a swipe point at the
         * same pixel produce the same model-space position.
         */
        @JvmStatic
        fun normalizedTapPosition(rawX: Int, rawY: Int): FloatArray? {
            if (rawX < 0 || rawY < 0) return null   // Constants.NOT_A_COORDINATE
            val n = currentNormalizers() ?: return null
            return floatArrayOf(n.x(rawX.toFloat()), n.y(rawY.toFloat()))
        }

        /**
         * Pixel padding between the view frame the gesture path records in and the keyboard frame
         * everything else uses. Non-zero means swipe points sit offset from the key centres the
         * model was calibrated against. Debug readout only.
         */
        @JvmStatic
        fun debugFrameOffset(): IntArray {
            val kb = prevKeyboard ?: return intArrayOf(-1, -1, -1, -1)
            return intArrayOf(
                kb.mPadding.left, kb.mPadding.top, kb.mBaseWidth,
                kb.mBaseHeight - kb.mPadding.bottom
            )
        }

        /**
         * Half the size of a typical letter key, in normalized layout space.
         *
         * The adaptive model expresses caps and scatter limits as fractions of a key, so it needs
         * to know how big a key *is* in the space its offsets live in. Measured from the applied
         * layout rather than the raw keyboard so it stays in the model's space even when the
         * layout transform is not identity.
         */
        @JvmStatic
        fun normalizedKeyHalfExtent(): FloatArray? {
            val kb = prevKeyboard ?: return null
            val info = appliedLayoutInfo
            if (info.letters.isEmpty() || kb.mBaseWidth <= 0) return null
            val key = kb.sortedKeys.firstOrNull { Character.isLetter(it.code) } ?: return null
            val w = kb.mBaseWidth.toFloat()
            val h = (kb.mBaseHeight - kb.mPadding.bottom).toFloat()
            if (w <= 0f || h <= 0f) return null
            val halfW = (key.width / w) * info.sx * 0.5f
            val halfH = (key.height / h) * (4.0f / 3.0f) * info.sy * 0.5f
            if (halfW <= 0f || halfH <= 0f) return null
            return floatArrayOf(halfW, halfH)
        }

        /**
         * Width of a letter key in keyboard view pixels, or 0 when no layout has been applied.
         *
         * For deciding whether a drawn stroke stayed within one key, which is what separates a tap
         * from a swipe on screen. In view pixels rather than normalized units because the drawing
         * works in view pixels, and converting the threshold once is cheaper than converting every
         * point.
         */
        @JvmStatic
        fun letterKeyWidthPx(): Float {
            val kb = prevKeyboard ?: return 0f
            val key = kb.sortedKeys.firstOrNull { Character.isLetter(it.code) } ?: return 0f
            return key.width.toFloat()
        }

        /**
         * The box of the key under a view point, as left, top, right, bottom in view pixels.
         *
         * For deciding whether a drawn stroke stayed on one key. Null when no layout is applied or
         * the point is not on a key.
         */
        @JvmStatic
        fun keyBoundsAt(x: Float, y: Float): FloatArray? {
            val kb = prevKeyboard ?: return null
            val xi = x.toInt()
            val yi = y.toInt()
            val key = kb.getNearestKeys(xi, yi).firstOrNull { it.isOnKey(xi, yi) } ?: return null
            return floatArrayOf(
                key.x.toFloat(), key.y.toFloat(),
                (key.x + key.width).toFloat(), (key.y + key.height).toFloat()
            )
        }

        /** Which bucket of learned geometry the current layout and orientation belong to. */
        @JvmStatic
        fun currentTouchModelLayoutKey(): String? {
            val info = appliedLayoutInfo
            if (info.letters.isEmpty()) return null
            val kb = prevKeyboard ?: return null
            val landscape = kb.mBaseWidth > (kb.mBaseHeight - kb.mPadding.bottom)
            return TapSwipeTouchModel.layoutKey(info.letters, landscape)
        }

        /**
         * The most recent decode's winning word and how far clear of the runner-up it was.
         *
         * Captured here because the margin exists only inside the decoder call - by the time the
         * word commits, only the chosen string survives. Learning is gated on it: a word the beam
         * search barely preferred is not evidence about aim.
         */
        /**
         * The word segmenter's verdict for one stroke list. See [segmentationFor].
         *
         * [ranges] holds each word's strokes as from, to pairs.
         */
        class Segmentation(
            @JvmField val generation: Int,
            @JvmField val strokeCount: Int,
            @JvmField val words: List<String>,
            @JvmField val ranges: List<IntArray>
        )

        @Volatile
        private var lastSegmentation: Segmentation? = null

        /**
         * The segmenter's verdict for exactly this session generation and stroke count, or null.
         *
         * Computed on the decode thread after each full decode, read on the main thread when the word
         * ends. The two checks are the invariant: a verdict may only be applied to the stroke list it
         * was computed from. A stroke added since does not bump the generation, so the count is
         * checked too.
         */
        @JvmStatic
        fun segmentationFor(generation: Int, strokeCount: Int): Segmentation? {
            val s = lastSegmentation ?: return null
            return if (s.generation == generation && s.strokeCount == strokeCount) s else null
        }

        @Volatile
        @JvmStatic
        var lastDecodeWord: String = ""
            private set

        @Volatile
        @JvmStatic
        var lastDecodeMargin: Float = 0f
            private set

        /**
         * The key positions most recently handed to `setMode`, i.e. what the encoder's `layout_keys`
         * tensor actually holds.
         *
         * Kept so the mechanism can be checked directly. Asserting on a decoded *word* instead
         * conflates two questions - whether the shift reaches the model, and whether a shift that
         * size is enough to change the answer - and the second can honestly be "no" while the
         * feature works perfectly.
         */
        @Volatile
        @JvmStatic
        var lastAppliedCx: FloatArray? = null
            private set

        @Volatile
        @JvmStatic
        var lastAppliedCy: FloatArray? = null
            private set

        /** How far a letter's installed position sits from its nominal one, or null if unknown. */
        @JvmStatic
        fun debugInstalledShift(codePoint: Int): FloatArray? {
            val info = appliedLayoutInfo
            val cx = lastAppliedCx ?: return null
            val cy = lastAppliedCy ?: return null
            val idx = info.letters.indexOf(Character.toLowerCase(codePoint).toChar())
            if (idx < 0 || idx >= cx.size || idx >= cy.size) return null
            if (idx >= info.xs.size || idx >= info.ys.size) return null
            return floatArrayOf(cx[idx] - info.xs[idx], cy[idx] - info.ys[idx])
        }

        @JvmStatic
        fun noteDecodeOutcome(word: String, margin: Float) {
            lastDecodeWord = word
            lastDecodeMargin = margin
        }

        /** The keyboard the decoder is currently configured for; used by the scenario runner. */
        @JvmStatic
        fun debugCurrentKeyboard(): Keyboard? = prevKeyboard

        @JvmStatic
        fun canBeUsed(): Boolean {
            val settings = Settings.getInstance().current
            if(!settings.mGestureInputEnabled) {
                Log.d("SwipeDecoderDictionary", "Inactive because gesture input is disabled.")
                return false
            }

            return true
        }
    }

    var decoder: SwipeDecoder? = null

    object BeamValues {
        const val shortBeam = 32
        const val midBeam = 64
        const val highBeam = 300
        const val highestBeam = 300
    }

    private fun getOrInitDecoder(): SwipeDecoder = decoder ?: run {
        val swipeModelPath = getFilePath(context, SWIPE_MODEL)

        val decoder = SwipeDecoder(
            encoderPath = swipeModelPath,
            beamWidth = BeamValues.highestBeam,
            useExpansion = false, // ITrie contains expanded entries already
        )

        this.decoder = decoder
        applyPendingLayoutInfo()

        return decoder
    }

    override fun getNextValidCodePoints(composedData: ComposedData?): ArrayList<Int> {
        return arrayListOf()
    }

    /**
     * Exposes the lazily-created decoder for the TapSwipe Phase 0 spikes
     * (see tapSwipe/TapSwipeSpikes.kt). Debug tooling only - the normal decode path
     * goes through [getSuggestions].
     */
    fun debugGetOrInitDecoder(): SwipeDecoder = getOrInitDecoder()

    private fun getPredictions(
        composedData: ComposedData,
        ngramContext: NgramContext?
    ): ArrayList<SuggestedWords.SuggestedWordInfo>? {
        if(true) return null

        val decoder = getOrInitDecoder()
        val wordsContext = ngramContext?.fullContext?.split(' ')?.takeLast(10) ?: emptyList()
        decoder.setContext(wordsContext)

        val results = decoder.predictNext()

        //Log.d("SwipeDecoderDictionary", "getPredictions results=${results}")
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>(results.size)
        results.forEach {
            list.add(SuggestedWords.SuggestedWordInfo(
                it.word, "", (it.score * 1000.0f + 10000.0f).toInt(), SuggestedWords.SuggestedWordInfo.KIND_CORRECTION, this, 0, 0
            ).apply {
                mOriginatesFromSwipeModel = true
            })
        }

        return list
    }

    override fun getSuggestions(
        composedData: ComposedData?,
        ngramContext: NgramContext?,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion?,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray?
    ): ArrayList<SuggestedWords.SuggestedWordInfo?>? {
        throw UnsupportedOperationException("Use the non-dictionary method instead")
    }

    val whitespaceRegex = Regex("[^\\w']+")
    fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext?,
        useHighBeam: Boolean,
        trieWeights: FloatArray
    ): ArrayList<SuggestedWords.SuggestedWordInfo>? {
        if(context.getSetting(LegacySwipeSetting) == true) return null

        if(!composedData.mIsBatchMode && composedData.mInputPointers.pointerSize == 0 && composedData.mTypedWord.isEmpty()) {
            return getPredictions(
                composedData,
                ngramContext
            )
        }

        // TapSwipe: a word accumulates strokes across finger lifts, so when session evidence is
        // present it supersedes the single-batch pointer data entirely.
        (composedData.mTapSwipeInput as? TapSwipeDecodeInput)?.let { tapSwipe ->
            return decodeTapSwipe(tapSwipe, ngramContext, useHighBeam, trieWeights)
        }

        // TapSwipe is on but carried no evidence for this query. Falling through to the legacy
        // single-batch path would decode `mInputPointers`, which still holds only the *last*
        // gesture - re-deriving a swipe-only word and overwriting one that taps had already
        // completed ("but" reverting to "by"). Whatever is currently composed is a better answer
        // than a decode of partial evidence, so decline instead.
        if(DataStoreHelper.getSetting(TapSwipeModeSetting)) {
            if(BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil) {
                Log.d("SwipeDecoderDictionary", "tapswipe on but no session input; declining " +
                    "legacy batch decode (batchMode=${composedData.mIsBatchMode})")
            }
            return null
        }

        if(!composedData.mIsBatchMode) return null

        val pointers = composedData.mInputPointers
        val segments = pointers.gestureSegments.toList().filter { it.x.length > 0 }

        val count = segments.size
        //Log.d("BatchInputSwipeDecoderDictionary", "total count is $count out of ${pointers.gestureSegments.size}")
        if(count == 0) return null

        val kb = prevKeyboard ?: run {
            Log.e("SwipeDecoderDictionary", "Could not determine keyboard!")
            return null
        }

        val keyboardWidth = kb.mBaseWidth
        val keyboardHeight = kb.mBaseHeight - kb.mPadding.bottom

        val earliestTime = segments[0].t.get(0).toFloat()
        val transformSegment = { seg: InputPointers.GestureSegment -> SwipeDecoder.SwipeSeg(
            x = seg.x.primitiveArray.take(seg.x.length).map {
                it.toFloat() / keyboardWidth * appliedLayoutInfo.sx + appliedLayoutInfo.ox
            }.toFloatArray(),
            y = seg.y.primitiveArray.take(seg.y.length).map {
                minOf(1.0f, (it.toFloat() / keyboardHeight) * (4.0f / 3.0f) * appliedLayoutInfo.sy + appliedLayoutInfo.oy )
            }.toFloatArray(),
            t = seg.t.primitiveArray.take(seg.t.length).map { it - earliestTime }.toFloatArray()
        ) }

        val left = mutableListOf<SwipeDecoder.SwipeSeg>()
        val right = mutableListOf<SwipeDecoder.SwipeSeg>()

        if(count == 1) {
            left.add(transformSegment(segments.first()))
        } else {
            left.addAll(segments.filter { it.pointerId == 0 }.map { transformSegment(it) })
            right.addAll(segments.filter { it.pointerId == 1 }.map { transformSegment(it) })
        }

        val wordsContext = ngramContext?.fullContext
            ?.lineSequence()
            ?.lastOrNull()
            ?.splitToSequence(whitespaceRegex)
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?.takeLast(10)
            ?: emptyList()

        val decoder = getOrInitDecoder()
        decoder.setContext(wordsContext)
        appliedTrieWeights = trieWeights

        val isMultiSwipe = (left.size + right.size) > 1
        val beamWidth = when {
            isMultiSwipe && !useHighBeam -> BeamValues.midBeam
            !useHighBeam -> BeamValues.shortBeam
            else -> BeamValues.highBeam
        }

        val topK = if(useHighBeam) 4 else 1

        val results = synchronized(BinaryDictionary.sTrieUsageLock) {
            if(appliedTries?.isEmpty() != false) {
                Log.e("SwipeDecoderDictionary", "Applied tries are blank! $appliedTries")
                return null
            }
            decoder.recognize(
                 left.toTypedArray(), right.toTypedArray(),
                 topK = topK,
                 beamWidth = beamWidth,
                 trieWeights = trieWeights
            )
        }

        // basically update it at end of swiping
        if(useHighBeam) appliedScoring.value = decoder.scoring

        // The learner checks that a committed word was the decoder's clear first choice. The TapSwipe
        // path reports that; the stock path now does too, so learned geometry works without the
        // input model.
        if (useHighBeam && results.isNotEmpty()) {
            val margin = if (results.size > 1) results[0].score - results[1].score else Float.MAX_VALUE
            noteDecodeOutcome(results[0].word, margin)
        }

        if(BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil) {
            Log.d("SwipeDecoderDictionary", "Beam width: ${beamWidth} (multiswipe=$isMultiSwipe useHighBeam=$useHighBeam)")
            Log.d("SwipeDecoderDictionary", "Timing: ${decoder.lastTiming()}")
            Log.d("SwipeDecoderDictionary", "Left = $left")
            Log.d("SwipeDecoderDictionary", "Right = $right")

            if(left.size == 1) {
                val lf = left.first()
                var lastT = 0
                val ls = (lf.x.zip(lf.y)).zip(lf.t.toList()).map { v ->
                    val x = (v.first.first * 100).roundToInt() / 100.0f
                    val y = (v.first.second * 1000).roundToInt() / 1000.0f
                    val t = v.second.toInt()

                    SwipePoiSer(x, y, t)
                }.filter {
                    if(it.t == 0 || (it.t - lastT) > 33) {
                        lastT = it.t
                        true
                    } else {
                        false
                    }
                }

                val ser = Json.encodeToString(SwipeSegSer.serializer(), SwipeSegSer(ls))
                Log.d("SwipeDecoderDictionary", "json = $ser")
            }

            Log.d("SwipeDecoderDictionary", "Context = $wordsContext")
            Log.d("SwipeDecoderDictionary", "curr scale is  ${appliedLayoutInfo.sx} ${appliedLayoutInfo.sy}")
            Log.d("SwipeDecoderDictionary", "curr offset is ${appliedLayoutInfo.ox} ${appliedLayoutInfo.oy}")
            Log.d("SwipeDecoderDictionary", "outputs = ${results.joinToString { "Word(\"${it.word}\", score=${it.score}, lm=${it.lmScore}, ctc=${it.ctcScore})" }}")
        }

        val list = ArrayList<SuggestedWords.SuggestedWordInfo>(results.size)
        results.forEach {
            list.add(SuggestedWords.SuggestedWordInfo(
                it.word, "", (it.score * 1000.0f + 10000.0f).toInt(), SuggestedWords.SuggestedWordInfo.KIND_CORRECTION, this, 0, 0
            ).apply {
                mOriginatesFromSwipeModel = true
            })
        }

        return list
    }

    private fun contextWordsFrom(ngramContext: NgramContext?): List<String> =
        ngramContext?.fullContext
            ?.lineSequence()
            ?.lastOrNull()
            ?.splitToSequence(whitespaceRegex)
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?.takeLast(10)
            ?: emptyList()

    /**
     * Context for re-decoding a TapSwipe word that is already composing.
     *
     * The text before the cursor includes the word being composed, so [contextWordsFrom] handed the
     * language model the word itself (or its literal, such as "fromr") as the previous word. Measured
     * 2026-11-11: 117 of 180 TapSwipe decodes in a typing test. Drop that last token when the cursor
     * is inside a word.
     */
    /**
     * Runs [TapSwipeSegmenter] over the finished strokes and stores the verdict for the finalizer.
     *
     * A swiped part is decoded on its own, with the tapped words before it as context. A tapped word
     * is exact text, so it has no shape to fit: it scores as a perfect shape, which leaves the
     * decoder's length bonus and the context model's view of that word coming next.
     */
    private fun segmentWord(
        input: TapSwipeDecodeInput, context: List<String>, whole: Pair<String, Float>,
        trieWeights: FloatArray
    ) {
        val started = System.nanoTime()
        val strokes = input.strokes
        val infos = strokes.map {
            TapSwipeSegmenter.StrokeInfo(it.isSwipe,
                if (it.codePoint > 0) Character.toLowerCase(it.codePoint.toChar()) else '?')
        }
        if (infos.none { it.isSwipe } || (infos.first().isSwipe && infos.last().isSwipe)) {
            // Nothing to judge: one word. Recorded, so the finalizer knows not to wait.
            lastSegmentation = Segmentation(input.generation, strokes.size, listOf(whole.first),
                listOf(intArrayOf(0, strokes.size)))
            return
        }
        val decoder = getOrInitDecoder()
        val scoring = decoder.scoring
        val predictions = HashMap<List<String>, List<SwipeDecoder.Result>>()
        val scorer = object : TapSwipeSegmenter.Scorer {
            override fun decode(from: Int, to: Int, context2: List<String>): Pair<String, Float>? {
                if (from == 0 && to == strokes.size && context2 == context) return whole
                val part = strokes.subList(from, to)
                if (part.none { it.isSwipe }) return null
                val left = part.filter { !it.right }.map { it.seg }.toMutableList()
                val right = part.filter { it.right }.map { it.seg }.toMutableList()
                if (left.isEmpty()) { left.addAll(right); right.clear() }
                decoder.setContext(context2)
                val r = synchronized(BinaryDictionary.sTrieUsageLock) {
                    if (appliedTries?.isEmpty() != false) null
                    // Four candidates, as the normal decode asks for: with one, the search dropped
                    // `think` and returned `tink` for a clean swipe of `think`.
                    else decoder.recognize(left.toTypedArray(), right.toTypedArray(), topK = 4,
                        beamWidth = BeamValues.highBeam, trieWeights = trieWeights)
                } ?: return null
                val top = r.maxByOrNull { it.score } ?: return null
                return top.word to top.score
            }

            override fun tappedWord(word: String, context2: List<String>): Float? {
                val w = word.lowercase()
                if (w.length == 1 && w !in TapSwipeSegmenter.ONE_LETTER_WORDS) return null
                val list = predictions.getOrPut(ArrayList(context2)) {
                    decoder.setContext(context2)
                    decoder.predictNext(TAPPED_WORD_PREDICTIONS)
                }
                val hit = list.firstOrNull { it.word.equals(w, ignoreCase = true) }
                org.futo.inputmethod.latin.tapswipe.TypingTestEvents.tappedWordScore(w,
                    context2.takeLast(3).joinToString(" "), hit?.score ?: 0f, hit?.lmScore ?: 0f, hit != null)
                val lm = when {
                    hit != null -> hit.lmScore
                    // A common short word the model does not expect here: allowed, but scored below
                    // every word it does expect.
                    w in TapSwipeSegmenter.COMMON_SHORT_WORDS || w in TapSwipeSegmenter.ONE_LETTER_WORDS ->
                        (list.minOfOrNull { it.lmScore } ?: 0f) - UNEXPECTED_TAPPED_WORD
                    // Not a likely next word and not a common one: not a word that stands alone.
                    else -> return null
                }
                return scoring.alpha * lm + scoring.beta * w.length
            }
        }
        val result = TapSwipeSegmenter.segment(infos, context, scorer)
        val ms = (System.nanoTime() - started) / 1_000_000f
        if (result == null) {
            lastSegmentation = Segmentation(input.generation, strokes.size, listOf(whole.first),
                listOf(intArrayOf(0, strokes.size)))
            return
        }
        val best = result.best
        lastSegmentation = Segmentation(input.generation, strokes.size, best.words,
            best.pieces.map { intArrayOf(it.from, it.to) })
        org.futo.inputmethod.latin.tapswipe.TypingTestEvents.segment(best.toString(),
            result.considered.sortedByDescending { it.score }.take(6).joinToString(" | "),
            input.generation, strokes.size, ms)
        if (BuildConfig.DEBUG) Log.d("SwipeDecoderDictionary", "tapswipe SEGMENT ${"%.0f".format(ms)} ms: $best")
    }

    private fun contextWordsBeforeComposing(ngramContext: NgramContext?): List<String> {
        val line = ngramContext?.fullContext?.lineSequence()?.lastOrNull() ?: return emptyList()
        val words = org.futo.inputmethod.latin.tapswipe.TapSwipeContext.words(line).takeLast(10)
        return org.futo.inputmethod.latin.tapswipe.TapSwipeContext.dropComposing(line, words)
    }

    private fun resultsToSuggestions(
        results: List<SwipeDecoder.Result>,
        penalties: List<Float> = emptyList()
    ): ArrayList<SuggestedWords.SuggestedWordInfo> {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>(results.size)
        results.forEachIndexed { i, it ->
            val score = it.score - (penalties.getOrNull(i) ?: 0f)
            list.add(SuggestedWords.SuggestedWordInfo(
                it.word, "", (score * 1000.0f + 10000.0f).toInt(),
                SuggestedWords.SuggestedWordInfo.KIND_CORRECTION, this, 0, 0
            ).apply {
                mOriginatesFromSwipeModel = true
            })
        }
        return list
    }

    /**
     * Decodes accumulated TapSwipe word-session evidence. Coordinates arrive already normalized
     * into the layout's space (the session applies the same transform as [transformSegment]),
     * so this only selects beam parameters and runs the decoder.
     *
     * Returns null in peck mode - a word with no swipe in it must not receive gesture
     * suggestions at all.
     */
    private fun decodeTapSwipe(
        input: TapSwipeDecodeInput,
        ngramContext: NgramContext?,
        useHighBeam: Boolean,
        trieWeights: FloatArray
    ): ArrayList<SuggestedWords.SuggestedWordInfo>? {
        if (!input.hasSwipe) return null
        if (input.isEmpty) return null

        val decoder = getOrInitDecoder()
        decoder.setContext(contextWordsBeforeComposing(ngramContext))
        appliedTrieWeights = trieWeights

        val isMultiSegment = input.segmentCount > 1
        val beamWidth = when {
            useHighBeam -> BeamValues.highBeam
            isMultiSegment -> BeamValues.midBeam
            else -> BeamValues.shortBeam
        }
        // A path through the apostrophe key, or a tap on it, is deliberate. The decoder's dictionary
        // and language model rate "I'm" far below "in", so ask for more candidates to keep the
        // apostrophe reading in the list, and penalize readings without one. See TapSwipeApostrophe.
        val apostropheVisited = useHighBeam && run {
            val half = normalizedKeyHalfExtent()
            half != null && org.futo.inputmethod.latin.tapswipe.TapSwipeApostrophe.visited(
                (input.left.toList() + input.right.toList()).map { it.x to it.y },
                normalizedKeyPosition('\''.code), half[0] * 2f, half[1] * 2f)
        }
        val topK = if (useHighBeam) (if (apostropheVisited) 8 else 4) else 1

        val firstResults = synchronized(BinaryDictionary.sTrieUsageLock) {
            if (appliedTries?.isEmpty() != false) {
                Log.e("SwipeDecoderDictionary", "Applied tries are blank! $appliedTries")
                return null
            }
            decoder.recognize(
                input.left, input.right,
                topK = topK,
                beamWidth = beamWidth,
                trieWeights = trieWeights
            )
        }

        // The apostrophe was visited on purpose. The language model rates contractions as nearly
        // impossible (about -12 for "I'm" and "I'll", against +7 for "in"), so it either buries them
        // or drops them from the beam. Decode again without context, which is the scoring a field's
        // first word already gets, and use that if it holds an apostrophe reading. Shape alone tells
        // "I'll" from "I'm". Only when the apostrophe was visited, so it costs nothing elsewhere.
        val results = if (apostropheVisited) {
            decoder.setContext(emptyList())
            val plain = synchronized(BinaryDictionary.sTrieUsageLock) {
                if (appliedTries?.isEmpty() != false) null
                else decoder.recognize(input.left, input.right, topK = topK, beamWidth = beamWidth,
                    trieWeights = trieWeights)
            }
            if (plain != null && plain.any { it.word.contains('\'') }) plain else firstResults
        } else firstResults

        if (useHighBeam) appliedScoring.value = decoder.scoring

        // Soft order across the two thumbs' streams. The decoder interleaves them by lexicon alone;
        // timing breaks the tie it cannot see. See TapSwipeTimingOrder.
        val rerankStart = System.nanoTime()
        val (timingOrder, timingPenalties) = if (useHighBeam && results.size > 1) {
            org.futo.inputmethod.latin.tapswipe.TapSwipeTimingOrder.rerank(
                results.map { it.word }, results.map { it.score }, input.timings, timingGeometry())
        } else {
            results.indices.toList() to List(results.size) { 0f }
        }
        val apostrophePenalties = org.futo.inputmethod.latin.tapswipe.TapSwipeApostrophe.penalties(
            results.map { it.word }, apostropheVisited)
        val penalties = timingPenalties.indices.map { timingPenalties[it] + apostrophePenalties[it] }
        // Keep the decoder's usual four candidates after re-ranking, so nothing downstream changes.
        val order = (if (apostropheVisited) results.indices.sortedByDescending { results[it].score - penalties[it] }
            else timingOrder).take(4)

        // Typing test / simulator only: the score breakdown, to study ranking.
        if (useHighBeam) {
            org.futo.inputmethod.latin.tapswipe.TypingTestEvents.decodeDetail(
                results.map { it.word }, results.map { it.score }, results.map { it.ctcScore },
                results.map { it.lmScore }, contextWordsBeforeComposing(ngramContext), decoder.scoring.alpha,
                decoder.scoring.beta, decoder.scoring.gamma, decoder.scoring.lambda,
                input.left.size, input.right.size, penalties, input.timings,
                (System.nanoTime() - rerankStart) / 1_000_000f,
                apostropheVisited, normalizedKeyPosition('\''.code), normalizedKeyHalfExtent(),
                normalizedKeyPosition('l'.code))
        }
        val ranked = order.map { results[it] }

        // Where the words are, when tap runs sit at the ends of the stroke list. Decided here, on the
        // decode thread, right after the decode of every finished stroke, and read by the finalizer.
        if (useHighBeam && input.strokes.size >= 2 && ranked.isNotEmpty()
            && DataStoreHelper.getSetting(TapSwipeAutoSplitSetting)) {
            val top = order[0]
            try {
                segmentWord(input, contextWordsBeforeComposing(ngramContext),
                    results[top].word to (results[top].score - penalties[top]), trieWeights)
            } catch (t: Throwable) {
                Log.e("SwipeDecoderDictionary", "segmentation failed", t)
            }
        }

        // Only the high-beam pass asks for more than one candidate, and it is the one that runs at
        // the end of a stroke - which is exactly the decode a commit will be based on.
        if (useHighBeam && results.isNotEmpty()) {
            val margin = if (results.size > 1) results[0].score - results[1].score else Float.MAX_VALUE
            noteDecodeOutcome(results[0].word, margin)
        }

        if (BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil) {
            Log.d("SwipeDecoderDictionary", "tapswipe $input beam=$beamWidth -> " +
                results.joinToString { "${it.word}(${it.score})" })
        }

        return resultsToSuggestions(ranked, penalties.let { p -> order.map { p[it] } })
    }

    /**
     * Nominal key positions with each key's learned shift added, or null when the feature is off or
     * there is nothing learned yet.
     *
     * Note this reads the extent from [appliedLayoutInfo], which is the *previous* layout at the
     * moment of a layout change. That is only used for the cap and scatter scale, where a small
     * discrepancy is harmless, and it self-corrects on the next reload.
     */
    private fun adaptPositions(
        layout: LayoutInfoForModel, baseX: FloatArray, baseY: FloatArray
    ): Pair<FloatArray, FloatArray>? {
        if (!DataStoreHelper.getSetting(TapSwipeAdaptiveGeometrySetting)) return null
        val extent = normalizedKeyHalfExtent() ?: return null
        val kb = prevKeyboard ?: return null
        val landscape = kb.mBaseWidth > (kb.mBaseHeight - kb.mPadding.bottom)
        val key = TapSwipeTouchModel.layoutKey(layout.letters, landscape)
        val now = System.currentTimeMillis()

        var changed = false
        val outX = baseX.copyOf()
        val outY = baseY.copyOf()
        for (i in layout.letters.indices) {
            if (i >= outX.size || i >= outY.size) break
            val shift = TapSwipeTouchModel.shiftFor(
                key, layout.letters[i].code, extent[0], extent[1], now) ?: continue
            outX[i] += shift[0]
            outY[i] += shift[1]
            changed = true
        }
        return if (changed) outX to outY else null
    }

    data class PendingLayoutInfo(val layout: LayoutInfoForModel, val tries: List<Long>)
    private var pendingLayoutInfo: PendingLayoutInfo? = null
    private fun applyPendingLayoutInfo() {
        decoder?.let { d ->
            pendingLayoutInfo?.let { pend ->
                //Log.d("SwipeDecoderDictionary", "Applying layout info: $pend")
                // Personalized key positions, when the user has opted in. This is the whole point
                // of the adaptive model: `cx`/`cy` become the encoder's `layout_keys` input, so
                // shifting them here is how the decoder learns where this person actually types.
                // Applied at layout-install time rather than per keystroke - learning is slow, and
                // setMode reloads the decoder.
                val baseX = pend.layout.xs.toFloatArray()
                val baseY = pend.layout.ys.toFloatArray()
                val adapted = adaptPositions(pend.layout, baseX, baseY)

                d.setMode(
                    letters=pend.layout.letters,
                    cx=adapted?.first ?: baseX,
                    cy=adapted?.second ?: baseY,
                    tries=pend.tries.toLongArray(),
                    decoderPath=getFilePath(context, pend.layout.decoder),
                    lmModelPath=getFilePath(context, pend.layout.lm),
                    lmVocabPath=getFilePath(context, vocabFor(pend.layout.lm))
                )
                lastAppliedCx = adapted?.first ?: baseX
                lastAppliedCy = adapted?.second ?: baseY
                appliedScoring.value = d.scoring
                appliedLayoutInfo = pend.layout
                appliedTries = pend.tries.toLongArray()
            }
            pendingLayoutInfo = null
        }
    }

    /**
     * Re-pushes the current layout so freshly learned geometry takes effect.
     *
     * Normally personalized positions land on the next natural layout install. This forces it, for
     * the scenario runner's mechanism check and for applying a reset immediately.
     */
    fun debugReinstallLayout() {
        val info = appliedLayoutInfo
        val tries = appliedTries ?: return
        if (info.letters.isEmpty()) return
        updateKeyboard(PendingLayoutInfo(info, tries.toList()))
    }

    fun updateKeyboard(pendingLayoutInfo: PendingLayoutInfo) {
        this.pendingLayoutInfo = pendingLayoutInfo
        applyPendingLayoutInfo()
    }

    override fun isInDictionary(word: String?): Boolean {
        return false
    }

    fun invalidateTries() {
        if(appliedTries?.isEmpty() != false) return
        decoder?.setMode(tries = emptyList<Long>().toLongArray())
        appliedTries = null
    }
}