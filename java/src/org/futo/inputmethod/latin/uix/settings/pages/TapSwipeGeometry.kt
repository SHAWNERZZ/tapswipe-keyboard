package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.inputmethod.latin.tapswipe.TapSwipeLearner
import org.futo.inputmethod.latin.tapswipe.TapSwipeTouchModel
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the keyboard has learned about where this person's fingers land.
 *
 * Built to be falsifiable rather than decorative. The adaptive model is otherwise invisible - a few
 * hundredths of a unit of shift buried in a tensor - so without a way to look at it there is no way
 * to tell "learning nothing", "learning noise", and "learning correctly" apart.
 *
 * Per key: a rounded outline for the key itself, a hollow ring at its nominal centre, a filled dot
 * at the target learning has moved it to, and an ellipse for the spread of the measurements. The
 * gap between ring and dot is the safety machinery working - when a key is capped or suppressed for
 * scatter, the raw measurement and the applied shift disagree, and both are shown, because
 * otherwise a suppressed key looks identical to one with no data.
 *
 * Everything is sized in dp. An earlier version passed raw floats to the draw calls, which Compose
 * treats as **pixels**, so on a 3x-density screen every marker rendered at a third of its intended
 * size and the labels were unreadable.
 */

// ---------------------------------------------------------------- geometry helpers

/** Maps normalized layout space onto the canvas, with room for a key's worth of margin. */
private class HeatmapTransform(
    val canvas: Size,
    val minX: Float, val minY: Float,
    val spanX: Float, val spanY: Float,
    val halfW: Float, val halfH: Float
) {
    private val padX = halfW * 1.6f
    private val padY = halfH * 1.6f
    private val worldW = spanX + padX * 2
    private val worldH = spanY + padY * 2

    val scaleX = if (worldW > 0f) canvas.width / worldW else 0f
    val scaleY = if (worldH > 0f) canvas.height / worldH else 0f

    fun x(nx: Float) = (nx - minX + padX) * scaleX
    fun y(ny: Float) = (ny - minY + padY) * scaleY

    val keyW get() = halfW * 2 * scaleX
    val keyH get() = halfH * 2 * scaleY

    companion object {
        fun of(canvas: Size, xs: List<Float>, ys: List<Float>, halfW: Float, halfH: Float):
                HeatmapTransform? {
            if (canvas.width <= 0f || canvas.height <= 0f) return null
            if (xs.isEmpty() || ys.isEmpty()) return null
            val minX = xs.min()
            val minY = ys.min()
            return HeatmapTransform(
                canvas, minX, minY,
                max(1e-4f, xs.max() - minX), max(1e-4f, ys.max() - minY),
                halfW, halfH
            )
        }
    }
}

/** Aspect ratio the mini keyboard should be drawn at, so it looks like the real thing. */
private fun keyboardAspect(xs: List<Float>, ys: List<Float>, halfW: Float, halfH: Float): Float {
    if (xs.isEmpty() || ys.isEmpty()) return 2.6f
    val w = (xs.max() - xs.min()) + halfW * 3.2f
    val h = (ys.max() - ys.min()) + halfH * 3.2f
    if (h <= 0f) return 2.6f
    return (w / h).coerceIn(1.2f, 4.5f)
}

/** Fraction of the animation spent redrawing the gesture before attribution begins. */
private const val PATH_PHASE = 0.45f

// ---------------------------------------------------------------- screen

/** Polls the model's revision counter so the page updates while you type in another app. */
@Composable
private fun rememberModelRevision(): Int {
    var rev by remember { mutableIntStateOf(TapSwipeTouchModel.revision) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (TapSwipeTouchModel.revision != rev) rev = TapSwipeTouchModel.revision
        }
    }
    return rev
}

/**
 * Names learning that exists but is filed under a different layout bucket.
 *
 * Without this the page shows nothing and reads as data loss, when in fact a layout key folds in
 * both the letter set and the orientation - so changing layout, or anything that changes which keys
 * count as letters, moves evidence somewhere this page was not looking. Nothing is ever deleted by
 * that, and saying so is the difference between a scare and a shrug.
 */
@Composable
private fun OtherLayoutNote(byLayout: Map<String, Int>, currentKey: String?) {
    val others = byLayout.filterKeys { it != currentKey }
    if (others.isEmpty()) return

    val total = others.values.sum()
    val described = others.entries.sortedByDescending { it.value }.joinToString("; ") { (key, n) ->
        val orientation = if (key.startsWith("L:")) "landscape" else "portrait"
        "$n in $orientation ${key.drop(2).take(30)}"
    }
    Text(
        "$total more samples are stored for other layouts and are not shown above — $described. " +
            "Learning is kept per layout and orientation; nothing here is deleted when you switch.",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun TapSwipeGeometryScreen(navController: androidx.navigation.NavHostController? = null) {
    val context = LocalContext.current

    // Read the model before anything here touches it. Settings can be opened without the keyboard
    // service ever having started, in which case nothing has loaded it yet - the page would show an
    // empty model, and Reset would then write that emptiness over a perfectly good file.
    LaunchedEffect(Unit) { TapSwipeTouchModel.ensureLoaded(context) }

    val revision = rememberModelRevision()
    var selected by remember { mutableStateOf<Int?>(null) }

    val layoutKey = SwipeDecoderDictionary.currentTouchModelLayoutKey()
    val extent = SwipeDecoderDictionary.normalizedKeyHalfExtent()
    val layoutInfo = SwipeDecoderDictionary.appliedLayoutInfo

    val stats = remember(revision, layoutKey) {
        if (layoutKey == null || extent == null) emptyList()
        else TapSwipeTouchModel.statsFor(layoutKey, extent[0], extent[1], System.currentTimeMillis())
    }
    val byCodePoint = remember(stats) { stats.associateBy { it.codePoint } }

    val replay = remember { Animatable(0f) }
    var replayData by remember { mutableStateOf<TapSwipeLearner.LastLearned?>(null) }
    val scope = rememberCoroutineScope()

    ScrollableList {
        ScreenTitle("Learned key geometry", showBack = true, navController = navController)

        // Read regardless of whether the current layout has anything, so that evidence held under
        // another bucket can be reported rather than looking like it was lost.
        val byLayout = remember(revision) { TapSwipeTouchModel.samplesByLayout() }

        if (layoutKey == null || extent == null || layoutInfo.letters.isEmpty()) {
            Text(
                "No keyboard layout has been loaded yet. Open the keyboard and swipe a word, " +
                    "then come back.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            OtherLayoutNote(byLayout, currentKey = layoutKey)
            return@ScrollableList
        }

        val totalSamples = stats.sumOf { it.count }
        Text(
            "$totalSamples samples across ${stats.size} keys",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall
        )
        OtherLayoutNote(byLayout, currentKey = layoutKey)
        Text(
            "Ring = where the key is. Dot = where your typing has moved it. " +
                "Ellipse = how consistent you are — a wide one means there is no habit to learn.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        GeometryHeatmap(
            letters = layoutInfo.letters,
            xs = layoutInfo.xs,
            ys = layoutInfo.ys,
            halfW = extent[0],
            halfH = extent[1],
            byCodePoint = byCodePoint,
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            replayProgress = replay.value,
            replayData = replayData,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        ReplayControls(
            playing = replay.isRunning,
            onPlay = {
                replayData = TapSwipeLearner.lastLearned
                scope.launch {
                    replay.snapTo(0f)
                    // Length follows the word's real duration, slowed, so a hurried word replays
                    // hurried and a deliberate one replays deliberate. Clamped at both ends: too
                    // short to follow is useless, and a long pause mid-word should not mean sitting
                    // through it. Relative timing within the word is preserved either way, which is
                    // the part that says something about the input.
                    val gestureMs = (replayData?.durationMs ?: 0f) * 2.5f
                    val totalMs = (gestureMs / PATH_PHASE).toInt().coerceIn(1600, 6000)
                    replay.animateTo(1f, tween(durationMillis = totalMs, easing = LinearEasing))
                }
            }
        )

        if (stats.isEmpty()) {
            Text(
                "Nothing learned yet. Turn on “Adaptive key geometry” in TapSwipe settings and " +
                    "swipe some words — only confident swipes with no overlapping strokes count.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        selected?.let { cp -> KeyDetail(cp, byCodePoint[cp], extent[0], extent[1]) }

        if (stats.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            TopMoversTable(stats, extent[0], extent[1]) { selected = it }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            TapSwipeLearner.Counters.summary(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "layout $layoutKey",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Button(onClick = {
                // Belt and braces alongside the LaunchedEffect above: reset writes to disk, and
                // resetting a model that was never read would discard what is on it.
                TapSwipeTouchModel.ensureLoaded(context)
                TapSwipeTouchModel.reset()
                TapSwipeTouchModel.save(context)
                selected = null
            }) {
                Text("Reset learned geometry")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ReplayControls(playing: Boolean, onPlay: () -> Unit) {
    val last = TapSwipeLearner.lastLearned
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedButton(onClick = onPlay, enabled = last != null && !playing) {
            Text(if (playing) "Playing…" else "Replay last learned word")
        }
        Text(
            if (last != null) {
                "Redraws the gesture that taught it “${last.word}”, then shows which part of the " +
                    "stroke was attributed to each letter and how far off centre it landed." +
                    (if (last.wasCorrection) " That one came from a correction, so it counted " +
                        "for extra." else "")
            } else {
                "Once a swipe has been learned from, this replays it — showing which part of the " +
                    "stroke was attributed to each letter."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
        )
    }
}

// ---------------------------------------------------------------- the heatmap

@Composable
private fun GeometryHeatmap(
    letters: String,
    xs: List<Float>,
    ys: List<Float>,
    halfW: Float,
    halfH: Float,
    byCodePoint: Map<Int, TapSwipeTouchModel.KeyStats>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    replayProgress: Float,
    replayData: TapSwipeLearner.LastLearned?,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // One transform, shared by drawing and hit-testing, so a tap always selects the key drawn under
    // the finger.
    val transform = remember(boxSize, xs, ys, halfW, halfH) {
        HeatmapTransform.of(
            Size(boxSize.width.toFloat(), boxSize.height.toFloat()), xs, ys, halfW, halfH
        )
    }

    val ringPx = with(density) { 5.dp.toPx() }
    val dotBasePx = with(density) { 3.dp.toPx() }
    val dotGrowPx = with(density) { 3.5.dp.toPx() }
    val thinPx = with(density) { 1.dp.toPx() }
    val boldPx = with(density) { 2.dp.toPx() }

    Box(
        modifier = modifier
            .aspectRatio(keyboardAspect(xs, ys, halfW, halfH))
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .onSizeChanged { boxSize = it }
            .pointerInput(transform, letters) {
                detectTapGestures { tap ->
                    val t = transform ?: return@detectTapGestures
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (i in letters.indices) {
                        if (i >= xs.size || i >= ys.size) break
                        val d = hypot(t.x(xs[i]) - tap.x, t.y(ys[i]) - tap.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    // Only count taps that landed near something.
                    if (best >= 0 && bestD < t.keyW) onSelect(letters[best].code)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = transform ?: return@Canvas

            // Keys first, so every marker sits on top of its own key.
            for (i in letters.indices) {
                if (i >= xs.size || i >= ys.size) break
                val cp = letters[i].code
                val cx = t.x(xs[i])
                val cy = t.y(ys[i])
                val isSel = selected == cp

                drawRoundRect(
                    color = if (isSel) scheme.primary.copy(alpha = 0.18f)
                            else scheme.onSurfaceVariant.copy(alpha = 0.07f),
                    topLeft = Offset(cx - t.keyW / 2 + thinPx, cy - t.keyH / 2 + thinPx),
                    size = Size(
                        (t.keyW - thinPx * 2).coerceAtLeast(1f),
                        (t.keyH - thinPx * 2).coerceAtLeast(1f)
                    ),
                    cornerRadius = CornerRadius(thinPx * 3)
                )

                val label = measurer.measure(
                    AnnotatedString(letters[i].toString()),
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (isSel) scheme.primary else scheme.onSurfaceVariant
                    ),
                    maxLines = 1
                )
                drawText(
                    label,
                    topLeft = Offset(cx - label.size.width / 2f, cy - t.keyH / 2 + thinPx * 2)
                )
            }

            // Learned state per key.
            for (i in letters.indices) {
                if (i >= xs.size || i >= ys.size) break
                val cp = letters[i].code
                val cx = t.x(xs[i])
                val cy = t.y(ys[i])
                val st = byCodePoint[cp]
                val isSel = selected == cp

                if (st != null) {
                    val ex = st.spreadX * t.scaleX
                    val ey = st.spreadY * t.scaleY
                    if (ex > 1f || ey > 1f) {
                        drawOval(
                            color = scheme.tertiary.copy(alpha = 0.18f),
                            topLeft = Offset(cx - ex, cy - ey),
                            size = Size(ex * 2, ey * 2)
                        )
                    }

                    val dx = cx + st.appliedDx * t.scaleX
                    val dy = cy + st.appliedDy * t.scaleY
                    val colour = shiftColour(st, halfW, halfH, scheme.primary, scheme.error)

                    drawLine(colour, Offset(cx, cy), Offset(dx, dy), strokeWidth = boldPx)
                    drawCircle(colour, dotBasePx + dotGrowPx * st.confidence, Offset(dx, dy))
                }

                drawCircle(
                    color = if (isSel) scheme.primary
                            else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    radius = ringPx,
                    center = Offset(cx, cy),
                    style = Stroke(width = if (isSel) boldPx else thinPx)
                )
            }

            if (replayProgress > 0f && replayData != null) {
                drawReplay(t, replayData, replayProgress, scheme, boldPx, dotBasePx, measurer)
            }
        }
    }
}

/**
 * Replays the last learned word, then shows how each letter was attributed.
 *
 * The gesture phase runs on the **recorded timestamps**, not at a uniform rate. Taps and swipes are
 * separate events that happened at particular moments, and flattening them into one evenly-drawn
 * polyline misrepresented the input twice over: a tap became a line joining it to wherever the
 * finger had last been, and the pause before it disappeared. When a tap landed relative to a swipe
 * is part of what the aligner had to work with, so the replay honours it.
 *
 * The attribution phase then draws, for each letter, a line from the point of the stroke assigned to
 * it back to that key's nominal centre - which is precisely the offset that gets recorded, and is
 * otherwise completely invisible.
 */
private fun DrawScope.drawReplay(
    t: HeatmapTransform,
    data: TapSwipeLearner.LastLearned,
    progress: Float,
    scheme: ColorScheme,
    boldPx: Float,
    dotPx: Float,
    measurer: TextMeasurer
) {
    val segments = data.segments
    if (segments.isEmpty()) return

    val t0 = segments.minOf { it.startT }
    val t1 = segments.maxOf { it.endT }
    val span = (t1 - t0).coerceAtLeast(1f)

    val gesturePhase = (progress / PATH_PHASE).coerceIn(0f, 1f)
    val now = t0 + span * gesturePhase

    for (seg in segments) {
        if (seg.startT > now) continue

        if (seg.isTap) {
            // A tap is an instant, drawn as a mark rather than a line. It flashes larger for a
            // moment after it lands, so its place in the sequence is visible rather than inferred.
            val age = ((now - seg.startT) / (span * 0.18f)).coerceIn(0f, 1f)
            val pop = 1f + (1f - age) * 1.1f
            drawCircle(
                scheme.primary, dotPx * 1.3f * pop,
                Offset(t.x(seg.x[0]), t.y(seg.y[0]))
            )
            drawCircle(
                scheme.primary.copy(alpha = 0.35f * (1f - age)),
                dotPx * 3f * pop,
                Offset(t.x(seg.x[0]), t.y(seg.y[0])),
                style = Stroke(width = boldPx)
            )
            continue
        }

        // Swipe: draw only as far as this moment reaches.
        var upTo = 0
        while (upTo + 1 < seg.t.size && seg.t[upTo + 1] <= now) upTo++
        if (upTo < 1) continue

        val path = Path().apply {
            moveTo(t.x(seg.x[0]), t.y(seg.y[0]))
            for (i in 1..upTo) lineTo(t.x(seg.x[i]), t.y(seg.y[i]))
        }
        drawPath(path, color = scheme.tertiary, style = Stroke(width = boldPx * 1.5f))

        // Leading edge while this stroke is still in progress, so direction of travel is obvious.
        if (gesturePhase < 1f && seg.endT > now) {
            drawCircle(
                scheme.tertiary, dotPx * 1.4f,
                Offset(t.x(seg.x[upTo]), t.y(seg.y[upTo]))
            )
        }
    }

    if (gesturePhase < 1f) return

    val attrs = data.attributions
    if (attrs.isEmpty()) return
    val attrPhase = ((progress - PATH_PHASE) / (1f - PATH_PHASE)).coerceIn(0f, 1f)
    val shown = (attrs.size * attrPhase).toInt().coerceAtMost(attrs.size)

    for (i in 0 until shown) {
        val a = attrs[i]
        val centre = SwipeDecoderDictionary.normalizedKeyPosition(a.codePoint) ?: continue
        val cx = t.x(centre[0])
        val cy = t.y(centre[1])
        val ax = t.x(centre[0] + a.dx)
        val ay = t.y(centre[1] + a.dy)

        // Taps are exact; swipe points are inferred, so they read as the weaker claim.
        val colour = if (a.fromTap) scheme.primary else scheme.tertiary
        val alpha = 0.35f + 0.65f * a.weight

        drawLine(colour.copy(alpha = alpha), Offset(ax, ay), Offset(cx, cy), strokeWidth = boldPx)
        drawCircle(colour.copy(alpha = alpha), dotPx * 1.2f, Offset(ax, ay))
        drawCircle(colour.copy(alpha = alpha), dotPx * 0.6f, Offset(cx, cy))
    }

    val label = measurer.measure(
        AnnotatedString(data.word),
        style = TextStyle(fontSize = 13.sp, color = scheme.onSurface),
        maxLines = 1
    )
    drawText(label, topLeft = Offset(dotPx * 2, dotPx * 2))
}

/** Red once a key is pushing against its cap - the shift is being limited rather than followed. */
private fun shiftColour(
    st: TapSwipeTouchModel.KeyStats, halfW: Float, halfH: Float, normal: Color, capped: Color
): Color {
    val capX = halfW * 2f * TapSwipeTouchModel.MAX_SHIFT_FRACTION
    val capY = halfH * 2f * TapSwipeTouchModel.MAX_SHIFT_FRACTION
    val atCap = abs(st.appliedDx) >= capX * 0.98f || abs(st.appliedDy) >= capY * 0.98f
    return if (atCap) capped else normal
}

// ---------------------------------------------------------------- detail panels

@Composable
private fun KeyDetail(
    codePoint: Int, st: TapSwipeTouchModel.KeyStats?, halfW: Float, halfH: Float
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Key “${codePoint.toChar()}”", style = MaterialTheme.typography.titleSmall)
        if (st == null) {
            Text("No samples yet.", style = MaterialTheme.typography.bodySmall)
            return
        }
        val pctX = pct(st.meanDx, halfW)
        val pctY = pct(st.meanDy, halfH)
        val apctX = pct(st.appliedDx, halfW)
        val apctY = pct(st.appliedDy, halfH)

        // Percentages of key size, not raw normalized units - "12% left of centre" is a thing you
        // can picture; "-0.0083" is not.
        MonoLine("measured   ${fmtPct(pctX)} x, ${fmtPct(pctY)} y  (of key size)")
        MonoLine("applied    ${fmtPct(apctX)} x, ${fmtPct(apctY)} y")
        MonoLine("spread     ${fmtPct(pct(st.spreadX, halfW))} x, ${fmtPct(pct(st.spreadY, halfH))} y")
        MonoLine("confidence ${"%.2f".format(st.confidence)}   samples ${st.count}")
        if (abs(apctX) < abs(pctX) * 0.9f || abs(apctY) < abs(pctY) * 0.9f) {
            Text(
                "Applied shift is smaller than measured — held back by confidence, scatter, or the cap.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun pct(v: Float, half: Float) = if (half > 0f) v / (half * 2f) * 100f else 0f

@Composable
private fun TopMoversTable(
    stats: List<TapSwipeTouchModel.KeyStats>,
    halfW: Float, halfH: Float,
    onSelect: (Int) -> Unit
) {
    val ranked = remember(stats) {
        stats.sortedByDescending { sqrt(it.appliedDx * it.appliedDx + it.appliedDy * it.appliedDy) }
            .take(10)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("Most-shifted keys", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        ranked.forEach { st ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(st.codePoint) }
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "“${st.codePoint.toChar()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "${fmtPct(pct(st.appliedDx, halfW))} x  " +
                        "${fmtPct(pct(st.appliedDy, halfH))} y   n=${st.count}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
}

private fun fmtPct(v: Float): String = (if (v >= 0) "+" else "") + "%.1f%%".format(v)
