package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.inputmethod.latin.tapswipe.TapSwipeTouchModel
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the keyboard has learned about where this person's fingers land.
 *
 * Built to be falsifiable rather than decorative. The adaptive model is otherwise invisible - a few
 * hundredths of a unit of shift buried in a tensor - so without a way to look at it there is no
 * way to tell "learning nothing", "learning noise", and "learning correctly" apart.
 *
 * Three things are on screen at once for every key, because they answer different questions:
 *
 *  - the **hollow ring** is the nominal key centre, the layout's idea of where the key is;
 *  - the **filled dot** is where the shift actually applied puts it, after caps and confidence;
 *  - the **ellipse** is the spread of the measurements, so a confidently-wrong key is visibly
 *    different from an honestly-uncertain one.
 *
 * The gap between the ring and the dot is the safety machinery working. When a key is capped or
 * suppressed for scatter, the raw measurement and the applied shift disagree, and both are shown -
 * otherwise a suppressed key would look exactly like a key with no data.
 */

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

@Composable
fun TapSwipeGeometryScreen(navController: androidx.navigation.NavHostController? = null) {
    val context = LocalContext.current
    val revision = rememberModelRevision()

    var selected by remember { mutableStateOf<Int?>(null) }

    // Recomputed whenever the model changes. Cheap - a few dozen keys.
    val layoutKey = SwipeDecoderDictionary.currentTouchModelLayoutKey()
    val extent = SwipeDecoderDictionary.normalizedKeyHalfExtent()
    val layoutInfo = SwipeDecoderDictionary.appliedLayoutInfo

    val stats = remember(revision, layoutKey) {
        if (layoutKey == null || extent == null) emptyList()
        else TapSwipeTouchModel.statsFor(layoutKey, extent[0], extent[1], System.currentTimeMillis())
    }
    val byCodePoint = remember(stats) { stats.associateBy { it.codePoint } }

    ScrollableList {
        ScreenTitle("Learned key geometry", showBack = true, navController = navController)

        if (layoutKey == null || extent == null || layoutInfo.letters.isEmpty()) {
            Text(
                "No keyboard layout has been loaded yet. Open the keyboard and swipe a word, " +
                    "then come back.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            return@ScrollableList
        }

        Text(
            "Each key shows its nominal centre (ring) and where your typing has moved it (dot). " +
                "The ellipse is how consistent you are - a wide one means there is no habit to learn.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val totalSamples = stats.sumOf { it.count }
        Text(
            "$totalSamples samples across ${stats.size} keys  ·  layout $layoutKey",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace
        )

        if (stats.isEmpty()) {
            Text(
                "Nothing learned yet. Turn on \"Adaptive key geometry\" in TapSwipe settings and " +
                    "swipe some words - only confident, single-stroke swipes count.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        GeometryHeatmap(
            letters = layoutInfo.letters,
            xs = layoutInfo.xs,
            ys = layoutInfo.ys,
            halfW = extent[0],
            halfH = extent[1],
            byCodePoint = byCodePoint,
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .aspectRatio(2.2f)
        )

        Legend()

        selected?.let { cp ->
            KeyDetail(cp, byCodePoint[cp], extent[0], extent[1])
        }

        Spacer(modifier = Modifier.height(8.dp))
        TopMoversTable(stats, extent[0], extent[1]) { selected = it }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Button(onClick = {
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
private fun GeometryHeatmap(
    letters: String,
    xs: List<Float>,
    ys: List<Float>,
    halfW: Float,
    halfH: Float,
    byCodePoint: Map<Int, TapSwipeTouchModel.KeyStats>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // The layout's own normalized coordinates drive the drawing, so this is the real key
    // arrangement rather than a hard-coded QWERTY mock - it stays correct on any layout.
    val minX = xs.minOrNull() ?: 0f
    val maxX = xs.maxOrNull() ?: 1f
    val minY = ys.minOrNull() ?: 0f
    val maxY = ys.maxOrNull() ?: 1f
    val spanX = max(1e-4f, maxX - minX)
    val spanY = max(1e-4f, maxY - minY)

    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(enabled = false) {}
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(1000.dp).let { Modifier.fillMaxWidth() }
            .aspectRatio(2.2f)) {
            canvasSize = size
            val padX = size.width * 0.06f
            val padY = size.height * 0.10f
            val w = size.width - padX * 2
            val h = size.height - padY * 2

            fun px(nx: Float) = padX + ((nx - minX) / spanX) * w
            fun py(ny: Float) = padY + ((ny - minY) / spanY) * h

            // Scale factors converting normalized offsets into on-screen pixels.
            val sx = w / spanX
            val sy = h / spanY

            for (i in letters.indices) {
                if (i >= xs.size || i >= ys.size) break
                val cp = letters[i].code
                val cxp = px(xs[i])
                val cyp = py(ys[i])
                val st = byCodePoint[cp]
                val isSel = selected == cp

                val ringColour = if (isSel) scheme.primary
                    else scheme.onSurfaceVariant.copy(alpha = 0.45f)

                if (st != null) {
                    // Spread ellipse first, so markers draw over it.
                    val ex = st.spreadX * sx
                    val ey = st.spreadY * sy
                    if (ex > 0.5f || ey > 0.5f) {
                        drawOval(
                            color = scheme.tertiary.copy(alpha = 0.16f),
                            topLeft = Offset(cxp - ex, cyp - ey),
                            size = Size(ex * 2, ey * 2)
                        )
                    }

                    val dxp = cxp + st.appliedDx * sx
                    val dyp = cyp + st.appliedDy * sy

                    // Line from nominal to applied, so direction reads at a glance.
                    drawLine(
                        color = shiftColour(st, halfW, halfH, scheme.primary, scheme.error),
                        start = Offset(cxp, cyp),
                        end = Offset(dxp, dyp),
                        strokeWidth = 2f
                    )
                    drawCircle(
                        color = shiftColour(st, halfW, halfH, scheme.primary, scheme.error),
                        radius = 3f + 3f * st.confidence,
                        center = Offset(dxp, dyp)
                    )
                }

                drawCircle(
                    color = ringColour,
                    radius = 4f,
                    center = Offset(cxp, cyp),
                    style = Stroke(width = if (isSel) 2.5f else 1.2f)
                )
            }
        }

        // Tap targets, laid over the canvas.
        if (canvasSize != Size.Zero) {
            KeyTapOverlay(letters, xs, ys, minX, spanX, minY, spanY, canvasSize, onSelect)
        }
    }
}

/**
 * Invisible hit targets over the canvas. Compose has no per-shape hit testing on a Canvas, so
 * selection is done with a transparent Box per key rather than by hand-rolling coordinate maths in
 * a pointer handler.
 */
@Composable
private fun KeyTapOverlay(
    letters: String,
    xs: List<Float>, ys: List<Float>,
    minX: Float, spanX: Float, minY: Float, spanY: Float,
    canvas: Size,
    onSelect: (Int) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val padX = canvas.width * 0.06f
    val padY = canvas.height * 0.10f
    val w = canvas.width - padX * 2
    val h = canvas.height - padY * 2
    val hit = 18f

    Box(modifier = Modifier.fillMaxWidth()) {
        for (i in letters.indices) {
            if (i >= xs.size || i >= ys.size) break
            val cp = letters[i].code
            val cxp = padX + ((xs[i] - minX) / spanX) * w
            val cyp = padY + ((ys[i] - minY) / spanY) * h
            with(density) {
                Box(
                    modifier = Modifier
                        .padding(start = (cxp - hit).toDp(), top = (cyp - hit).toDp())
                        .height((hit * 2).toDp())
                        .clickable { onSelect(cp) }
                ) {
                    Text(
                        letters[i].toString(),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = hit.toDp() - 3.dp)
                    )
                }
            }
        }
    }
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

@Composable
private fun Legend() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("ring = nominal centre   ·   dot = learned target (size = confidence)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("ellipse = spread of your taps   ·   red = shift is hitting its cap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyDetail(
    codePoint: Int, st: TapSwipeTouchModel.KeyStats?, halfW: Float, halfH: Float
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Key '${codePoint.toChar()}'", style = MaterialTheme.typography.titleSmall)
        if (st == null) {
            Text("No samples yet.", style = MaterialTheme.typography.bodySmall)
            return
        }
        val pctX = if (halfW > 0) st.meanDx / (halfW * 2f) * 100f else 0f
        val pctY = if (halfH > 0) st.meanDy / (halfH * 2f) * 100f else 0f
        val apctX = if (halfW > 0) st.appliedDx / (halfW * 2f) * 100f else 0f
        val apctY = if (halfH > 0) st.appliedDy / (halfH * 2f) * 100f else 0f

        // Percentages of key size, not raw normalized units - "12% left of centre" is a thing you
        // can picture; "-0.0083" is not.
        MonoLine("measured   ${fmtPct(pctX)} x, ${fmtPct(pctY)} y  (of key size)")
        MonoLine("applied    ${fmtPct(apctX)} x, ${fmtPct(apctY)} y")
        MonoLine("spread     ${fmtPct(if (halfW > 0) st.spreadX / (halfW * 2f) * 100f else 0f)} x, " +
                 "${fmtPct(if (halfH > 0) st.spreadY / (halfH * 2f) * 100f else 0f)} y")
        MonoLine("confidence ${"%.2f".format(st.confidence)}   samples ${st.count}")
        if (abs(apctX) < abs(pctX) * 0.9f || abs(apctY) < abs(pctY) * 0.9f) {
            Text(
                "Applied shift is smaller than measured - held back by confidence, scatter, or the cap.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopMoversTable(
    stats: List<TapSwipeTouchModel.KeyStats>,
    halfW: Float, halfH: Float,
    onSelect: (Int) -> Unit
) {
    if (stats.isEmpty()) return
    val ranked = remember(stats) {
        stats.sortedByDescending { sqrt(it.appliedDx * it.appliedDx + it.appliedDy * it.appliedDy) }
            .take(10)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("Most-shifted keys", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        ranked.forEach { st ->
            val dx = if (halfW > 0) st.appliedDx / (halfW * 2f) * 100f else 0f
            val dy = if (halfH > 0) st.appliedDy / (halfH * 2f) * 100f else 0f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(st.codePoint) }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("'${st.codePoint.toChar()}'",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace)
                Text("${fmtPct(dx)} x  ${fmtPct(dy)} y   n=${st.count}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace)
}

private fun fmtPct(v: Float): String =
    (if (v >= 0) "+" else "") + "%.1f%%".format(v)
