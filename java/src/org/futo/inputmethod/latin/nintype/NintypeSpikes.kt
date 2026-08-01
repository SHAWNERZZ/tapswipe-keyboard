package org.futo.inputmethod.latin.nintype

import android.util.Log
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.DictionaryFacilitatorImpl
import org.futo.inputmethod.latin.LayoutInfoForModel
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.ml.inference.SwipeDecoder

/**
 * Phase 0 feasibility spikes for the Nintype re-architecture (see NINTYPE_PLAN.md).
 *
 * These drive [SwipeDecoder] directly with synthetic trajectories built from the *applied*
 * layout's normalized key centers, so they exercise the decoder in exactly the coordinate
 * space the real path uses (see SwipeDecoderDictionary.transformSegment) without needing
 * any touch input or IME plumbing.
 *
 * Run from the Memory Debug action. Results also go to logcat under [TAG].
 */
object NintypeSpikes {
    const val TAG = "NintypeSpikes"

    /** Points generated per leg (letter-to-letter) of a synthetic swipe. */
    private const val POINTS_PER_LEG = 10

    /** Milliseconds between generated points; ~60Hz, matching a real stroke's sample rate. */
    private const val MS_PER_POINT = 16.0f

    /** Mirrors SwipeDecoderDictionary.BeamValues, so latency numbers reflect the real path. */
    private const val SHORT_BEAM = 32
    private const val HIGH_BEAM = 300

    // ---------------------------------------------------------------- synthesis

    private fun letterXY(layout: LayoutInfoForModel, ch: Char): Pair<Float, Float>? {
        val idx = layout.letters.indexOf(Character.toLowerCase(ch))
        if (idx < 0 || idx >= layout.xs.size || idx >= layout.ys.size) return null
        return layout.xs[idx] to layout.ys[idx]
    }

    /**
     * A continuous stroke passing through each letter of [letters] in order, linearly
     * interpolated between key centers. This is what a real swipe looks like to the encoder.
     */
    private fun stroke(
        layout: LayoutInfoForModel,
        letters: String,
        t0: Float,
        pointsPerLeg: Int = POINTS_PER_LEG
    ): SwipeDecoder.SwipeSeg? {
        val centers = letters.map { letterXY(layout, it) ?: return null }
        if (centers.isEmpty()) return null

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ts = ArrayList<Float>()

        // First point sits on the first key.
        xs.add(centers[0].first); ys.add(centers[0].second); ts.add(t0)

        for (i in 1 until centers.size) {
            val (ax, ay) = centers[i - 1]
            val (bx, by) = centers[i]
            for (s in 1..pointsPerLeg) {
                val f = s.toFloat() / pointsPerLeg
                xs.add(ax + (bx - ax) * f)
                ys.add(ay + (by - ay) * f)
                ts.add(t0 + ts.size * MS_PER_POINT)
            }
        }

        return SwipeDecoder.SwipeSeg(xs.toFloatArray(), ys.toFloatArray(), ts.toFloatArray())
    }

    /**
     * A tap: [points] samples held at one key center. [points] == 1 models the raw single
     * down-point a swallowed tap produces today; larger values model a synthesized dwell.
     */
    private fun tap(
        layout: LayoutInfoForModel,
        ch: Char,
        t0: Float,
        points: Int
    ): SwipeDecoder.SwipeSeg? {
        val (x, y) = letterXY(layout, ch) ?: return null
        val n = points.coerceAtLeast(1)
        return SwipeDecoder.SwipeSeg(
            FloatArray(n) { x },
            FloatArray(n) { y },
            FloatArray(n) { t0 + it * MS_PER_POINT }
        )
    }

    private fun SwipeDecoder.SwipeSeg.endT(): Float = if (t.isEmpty()) 0f else t[t.size - 1]

    // ---------------------------------------------------------------- execution

    private fun decode(
        decoder: SwipeDecoder,
        left: List<SwipeDecoder.SwipeSeg>,
        right: List<SwipeDecoder.SwipeSeg>,
        topK: Int = 4,
        beamWidth: Int = 300
    ): List<SwipeDecoder.Result> {
        val weights = SwipeDecoderDictionary.appliedTrieWeights
        return synchronized(BinaryDictionary.sTrieUsageLock) {
            val tries = SwipeDecoderDictionary.appliedTries
            if (tries == null || tries.isEmpty()) {
                throw IllegalStateException("no tries applied - open the keyboard and swipe once first")
            }
            decoder.recognize(
                left.toTypedArray(),
                right.toTypedArray(),
                topK = topK,
                beamWidth = beamWidth,
                trieWeights = weights
            )
        }
    }

    private fun fmt(results: List<SwipeDecoder.Result>): String =
        if (results.isEmpty()) "<empty>"
        else results.joinToString(", ") { "${it.word}(${"%.3f".format(it.score)})" }

    private fun hit(results: List<SwipeDecoder.Result>, expected: String): Boolean =
        results.any { it.word.equals(expected, ignoreCase = true) }

    private fun rank(results: List<SwipeDecoder.Result>, expected: String): Int =
        results.indexOfFirst { it.word.equals(expected, ignoreCase = true) }

    private fun StringBuilder.verdict(name: String, ok: Boolean, detail: String) {
        val line = "[${if (ok) "PASS" else "FAIL"}] $name — $detail"
        appendLine(line)
        Log.d(TAG, line)
    }

    // ---------------------------------------------------------------- the spikes

    /**
     * Runs all Phase 0 spikes and returns a human-readable report.
     * Safe to call repeatedly. Never throws; failures are captured into the report.
     */
    fun run(): String {
        val sb = StringBuilder()
        val layout = SwipeDecoderDictionary.appliedLayoutInfo

        sb.appendLine("layout letters = '${layout.letters}' (${layout.xs.size} keys)")
        if (layout.letters.length < 6 || layout.xs.size != layout.letters.length) {
            sb.appendLine("ABORT: no usable layout applied. Open the keyboard on an alphabet layout first.")
            return sb.toString()
        }

        val dict = DictionaryFacilitatorImpl.swipeDecoderDictionary
        if (dict == null) {
            sb.appendLine("ABORT: swipeDecoderDictionary is null.")
            return sb.toString()
        }

        val decoder = try {
            dict.debugGetOrInitDecoder()
        } catch (e: Throwable) {
            sb.appendLine("ABORT: could not init decoder: $e")
            return sb.toString()
        }

        decoder.clearContext()
        sb.appendLine("decoder: hasDecoder=${decoder.hasDecoder()} hasLm=${decoder.hasLm()}")
        sb.appendLine("tries=${SwipeDecoderDictionary.appliedTries?.size} weights=${SwipeDecoderDictionary.appliedTrieWeights.joinToString(",")}")
        sb.appendLine()

        s1(sb, decoder, layout)
        s2(sb, decoder, layout)
        s3(sb, decoder, layout)
        s4(sb, decoder, layout)
        s5(sb, decoder, layout)

        return sb.toString()
    }

    /**
     * S1 — multi-segment accumulation decodes. Splitting one word across two segments on the
     * same hand should still reach the word. This is the foundation of "lifts don't end the word".
     */
    private fun s1(sb: StringBuilder, decoder: SwipeDecoder, layout: LayoutInfoForModel) {
        sb.appendLine("== S1: multi-segment accumulation ==")
        try {
            val whole = stroke(layout, "hello", 0f) ?: run {
                sb.appendLine("  skipped: layout lacks needed letters"); return
            }
            val baseline = decode(decoder, listOf(whole), emptyList())
            sb.appendLine("  1 segment  'hello'      -> ${fmt(baseline)}")

            val a = stroke(layout, "hel", 0f)!!
            val b = stroke(layout, "lo", a.endT() + 120f)!!
            val split = decode(decoder, listOf(a, b), emptyList())
            sb.appendLine("  2 segments 'hel'+'lo'   -> ${fmt(split)}")

            sb.verdict(
                "S1", hit(split, "hello"),
                "expected 'hello' in top-4 from 2 segments (rank=${rank(split, "hello")})"
            )
        } catch (e: Throwable) {
            sb.verdict("S1", false, "threw: $e")
        }
        sb.appendLine()
    }

    /**
     * S2 — THE GATING SPIKE. Cross-hand interleaving resolved by the lexicon.
     *
     * decode_multi() receives no timestamps, only two emission streams, so the interleaving
     * between hands should be chosen by whatever spells a real word. Left 'saw' + right 'hn'
     * must therefore be able to produce 'shawn' (S,H,A,W,N).
     */
    private fun s2(sb: StringBuilder, decoder: SwipeDecoder, layout: LayoutInfoForModel) {
        sb.appendLine("== S2: cross-hand interleaving (GATING) ==")
        try {
            // Overlapping in time, as two thumbs would be.
            val left = stroke(layout, "saw", 0f) ?: run {
                sb.appendLine("  skipped"); return
            }
            val right = stroke(layout, "hn", 40f) ?: run {
                sb.appendLine("  skipped"); return
            }

            val both = decode(decoder, listOf(left), listOf(right))
            sb.appendLine("  L='saw' R='hn'          -> ${fmt(both)}")

            // Controls: each hand alone should NOT produce shawn.
            sb.appendLine("  L='saw' alone           -> ${fmt(decode(decoder, listOf(left), emptyList()))}")
            sb.appendLine("  R='hn'  alone           -> ${fmt(decode(decoder, listOf(right), emptyList()))}")

            sb.verdict(
                "S2", hit(both, "shawn"),
                "expected 'shawn' from interleaved hands (rank=${rank(both, "shawn")})"
            )

            // Second case with a common word, in case 'shawn' is not in the lexicon.
            val l2 = stroke(layout, "hlo", 0f)!!
            val r2 = stroke(layout, "el", 40f)!!
            val alt = decode(decoder, listOf(l2), listOf(r2))
            sb.appendLine("  L='hlo' R='el'          -> ${fmt(alt)}  (want 'hello')")
            sb.verdict("S2b", hit(alt, "hello"), "lexicon-driven interleave, common word")
        } catch (e: Throwable) {
            sb.verdict("S2", false, "threw: $e")
        }
        sb.appendLine()
    }

    /**
     * S3 — timestep budget. Each segment contributes 32 emission timesteps; the native lib
     * carries "Too many timesteps specified! %zu:%zu". Find the ceiling so the accumulator
     * can cap segments per hand.
     */
    private fun s3(sb: StringBuilder, decoder: SwipeDecoder, layout: LayoutInfoForModel) {
        sb.appendLine("== S3: timestep budget ==")
        var lastOk = 0
        var failure = "none"
        try {
            for (n in intArrayOf(2, 4, 6, 8, 12, 16, 20, 24, 32, 48, 64)) {
                val segs = ArrayList<SwipeDecoder.SwipeSeg>(n)
                var t = 0f
                for (i in 0 until n) {
                    val ch = layout.letters[i % layout.letters.length]
                    val s = tap(layout, ch, t, 4) ?: break
                    segs.add(s)
                    t = s.endT() + 80f
                }
                if (segs.size < n) break

                try {
                    val r = decode(decoder, segs, emptyList(), topK = 1, beamWidth = 64)
                    lastOk = n
                    sb.appendLine("  $n segments (~${n * 32} timesteps) -> ok, ${fmt(r)}")
                } catch (e: Throwable) {
                    failure = "$n segments: $e"
                    sb.appendLine("  $n segments -> FAILED: $e")
                    break
                }
            }
            sb.verdict("S3", lastOk > 0, "max segments/hand that decoded = $lastOk (first failure: $failure)")
        } catch (e: Throwable) {
            sb.verdict("S3", false, "threw: $e")
        }
        sb.appendLine()
    }

    /**
     * S4 — tap representation quality. A tap is off-distribution for a model trained on
     * swipes. Compare a raw 1-point tap against synthesized dwells of increasing length,
     * mixed with a real swipe: taps h,e,l then swipe l->o, expecting "hello".
     */
    private fun s4(sb: StringBuilder, decoder: SwipeDecoder, layout: LayoutInfoForModel) {
        sb.appendLine("== S4: tap representation ==")
        try {
            var best = -1
            var bestN = 0
            for (dwell in intArrayOf(1, 2, 4, 8, 16)) {
                var t = 0f
                val segs = ArrayList<SwipeDecoder.SwipeSeg>()
                var ok = true
                for (ch in "hel") {
                    val s = tap(layout, ch, t, dwell)
                    if (s == null) { ok = false; break }
                    segs.add(s)
                    t = s.endT() + 90f
                }
                if (!ok) { sb.appendLine("  skipped"); return }
                val sw = stroke(layout, "lo", t) ?: run { sb.appendLine("  skipped"); return }
                segs.add(sw)

                val r = try {
                    decode(decoder, segs, emptyList())
                } catch (e: Throwable) {
                    sb.appendLine("  dwell=$dwell -> threw: $e"); continue
                }
                val rk = rank(r, "hello")
                sb.appendLine("  dwell=${dwell.toString().padStart(2)} pts, taps h,e,l + swipe l->o -> ${fmt(r)}")
                if (rk >= 0 && (best < 0 || rk < best)) { best = rk; bestN = dwell }
            }
            sb.verdict(
                "S4", best >= 0,
                if (best >= 0) "best dwell = $bestN points ('hello' at rank $best)"
                else "'hello' never reached top-4 from taps+swipe"
            )
        } catch (e: Throwable) {
            sb.verdict("S4", false, "threw: $e")
        }
        sb.appendLine()
    }

    /**
     * S5 — latency of re-decoding an accumulated word. Under Nintype every new stroke re-decodes
     * the *whole* word, so cost grows with accumulated segment count (each one costs a
     * predict_segment pass, and the beam runs over the concatenated emissions).
     *
     * Budget: a mid-stroke update should stay well inside one frame (~16ms) at the low beam;
     * the finger-lift re-decode at high beam is the one to watch.
     */
    private fun s5(sb: StringBuilder, decoder: SwipeDecoder, layout: LayoutInfoForModel) {
        sb.appendLine("== S5: re-decode latency ==")
        try {
            var worstHighMs = 0.0
            for (segCount in intArrayOf(1, 2, 3, 4, 6, 8)) {
                val segs = ArrayList<SwipeDecoder.SwipeSeg>(segCount)
                var t = 0f
                for (i in 0 until segCount) {
                    val s = stroke(layout, "hel", t) ?: break
                    segs.add(s)
                    t = s.endT() + 90f
                }
                if (segs.size < segCount) break

                for ((label, beam, topK) in listOf(
                    Triple("low ", SHORT_BEAM, 1),
                    Triple("high", HIGH_BEAM, 4)
                )) {
                    // One warm-up, then take the best of 3 to reduce scheduler noise.
                    decode(decoder, segs, emptyList(), topK = topK, beamWidth = beam)
                    var bestMs = Double.MAX_VALUE
                    var timing = decoder.lastTiming()
                    repeat(3) {
                        val t0 = System.nanoTime()
                        decode(decoder, segs, emptyList(), topK = topK, beamWidth = beam)
                        val ms = (System.nanoTime() - t0) / 1_000_000.0
                        if (ms < bestMs) { bestMs = ms; timing = decoder.lastTiming() }
                    }
                    if (label == "high") worstHighMs = maxOf(worstHighMs, bestMs)
                    sb.appendLine(
                        "  ${segCount} seg beam=${beam.toString().padStart(3)} ($label) " +
                        "wall=${"%.1f".format(bestMs)}ms  " +
                        "enc=${"%.1f".format(timing.encoderUs / 1000f)} " +
                        "dec=${"%.1f".format(timing.decoderUs / 1000f)} " +
                        "beam=${"%.1f".format(timing.beamUs / 1000f)} " +
                        "lm=${"%.1f".format(timing.lmUs / 1000f)} " +
                        "tot=${"%.1f".format(timing.totalUs / 1000f)}ms"
                    )
                }
            }
            sb.verdict(
                "S5", worstHighMs < 100.0,
                "worst high-beam re-decode = ${"%.1f".format(worstHighMs)}ms (want well under 100ms)"
            )
        } catch (e: Throwable) {
            sb.verdict("S5", false, "threw: $e")
        }
        sb.appendLine()
    }
}
