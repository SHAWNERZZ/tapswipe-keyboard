package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.tapswipe.TypingTestJson
import org.futo.inputmethod.latin.tapswipe.TypingTestRecorder
import org.futo.inputmethod.latin.tapswipe.TypingTestScore
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Folder of word lists, in `java/assets-tapswipe/typing-tests/`. */
private const val LIST_DIR = "typing-tests"

private data class WordList(val name: String, val words: List<String>)

private fun assetLists(context: Context): List<WordList> =
    (context.assets.list(LIST_DIR) ?: emptyArray())
        .filter { it.endsWith(".txt") }
        .sorted()
        .map { file ->
            val text = context.assets.open("$LIST_DIR/$file").bufferedReader().use { it.readText() }
            WordList(file.removeSuffix(".txt"), TypingTestScore.parseWordList(text))
        }
        .filter { it.words.isNotEmpty() }

/**
 * Settings that shape typing, for the start of a recording. A replay is only meaningful against the
 * same setup, so the recording carries it.
 */
private suspend fun settingsSnapshot(context: Context): String {
    val prefs = context.dataStore.data.first().asMap()
    val j = TypingTestJson()
    prefs.entries
        .filter { (k, _) ->
            val n = k.name
            n.startsWith("tapswipe") || n.contains("swipe") || n.contains("subtype", ignoreCase = true)
        }
        .sortedBy { it.key.name }
        .forEach { (k, v) -> j.put(k.name, v.toString()) }
    return j.build()
}

private fun copyTo(context: Context, file: File, uri: Uri): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    }
    true
} catch (e: Exception) {
    false
}

/**
 * The typing test. The user types a list of words as they normally would, and every touch and
 * keyboard decision is recorded with the word they meant. See `docs/plans/TYPING_SIMULATOR.md`.
 */
@Composable
fun TypingTestScreen(navController: androidx.navigation.NavHostController? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists = remember { assetLists(context) }

    var selected by remember { mutableStateOf(lists.firstOrNull()) }
    var shuffle by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<List<String>>(emptyList()) }
    var typed by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    var deletions by remember { mutableIntStateOf(0) }
    val corrected = remember { mutableStateListOf<Int>() }
    var result by remember { mutableStateOf<TypingTestScore.Totals?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var fileToSave by remember { mutableStateOf<File?>(null) }
    val editText = remember { mutableStateOf<EditText?>(null) }
    var recordings by remember { mutableStateOf(TypingTestRecorder.list(context)) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val f = fileToSave
        if (uri != null && f != null) {
            status = if (copyTo(context, f, uri)) "Saved ${f.name}" else "Could not save ${f.name}"
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            val words = text?.let { TypingTestScore.parseWordList(it) } ?: emptyList()
            if (words.isNotEmpty()) {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".txt") ?: "file"
                selected = WordList(name, words)
            } else {
                status = "That file has no words."
            }
        }
    }

    fun setMarker(id: String?) {
        val et = editText.value ?: return
        et.privateImeOptions = id?.let { TypingTestRecorder.markerFor(it) }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(et)
    }

    fun finish(reason: String) {
        if (!running) return
        running = false
        val elapsed = if (startMs > 0) SystemClock.uptimeMillis() - startMs else 0L
        val totals = TypingTestScore.totals(prompt, typed, elapsed)
        TypingTestRecorder.emit(TypingTestRecorder.event("test_end")
            .put("reason", reason)
            .put("text", typed)
            .put("elapsedMs", elapsed)
            .put("wpm", totals.wpm.toFloat())
            .put("accuracy", totals.accuracy.toFloat())
            .put("correct", totals.correctWords)
            .put("wrong", totals.wrongWords)
            .put("missed", totals.missedWords)
            .put("extra", totals.extraWords)
            .put("deletions", deletions)
            .putInts("correctedPromptIndexes", corrected.toIntArray()))
        resultFile = TypingTestRecorder.stop()
        setMarker(null)
        result = totals
        recordings = TypingTestRecorder.list(context)
    }

    fun start() {
        val list = selected ?: return
        val words = if (shuffle) list.words.shuffled() else list.words
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = list.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "list" }
        val id = "typingtest-$stamp-$safeName"
        prompt = words
        typed = ""
        deletions = 0
        corrected.clear()
        result = null
        resultFile = null
        status = null
        startMs = 0L
        TypingTestRecorder.start(context, id)
        running = true
        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsSnapshot(context) }
            TypingTestRecorder.emit(TypingTestRecorder.event("test_start")
                .put("list", list.name)
                .put("shuffled", shuffle)
                .putStrings("words", words)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("device", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .putRaw("settings", settings))
        }
        editText.value?.let {
            it.setText("")
            it.requestFocus()
        }
        setMarker(id)
        editText.value?.let {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (running) {
                TypingTestRecorder.emit(TypingTestRecorder.event("test_end").put("reason", "left_screen"))
                TypingTestRecorder.stop()
            }
        }
    }

    val textColor = MaterialTheme.colorScheme.onSurface
    val good = Color(0xFF3FA35B)
    val bad = MaterialTheme.colorScheme.error

    ScrollableList {
        ScreenTitle("Typing test", showBack = true, navController)

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!running) {
                Text(
                    "Type the words as you normally would. Every touch is recorded with the word you " +
                        "meant, so the simulator can learn how you type. Only this field is recorded.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    lists.forEach { l ->
                        val chosen = selected?.name == l.name
                        if (chosen) Button(onClick = {}) { Text(l.name) }
                        else OutlinedButton(onClick = { selected = l }) { Text(l.name) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { openLauncher.launch(arrayOf("text/*")) }) { Text("Load list from file") }
                    Checkbox(checked = shuffle, onCheckedChange = { shuffle = it })
                    Text("Shuffle")
                }
                selected?.let { Text("${it.name}: ${it.words.size} words", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = { start() }, enabled = selected != null) { Text("Start") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { finish("user_finished") }) { Text("Finish now") }
                }
            }

            if (prompt.isNotEmpty()) {
                val typedWords = TypingTestScore.words(typed)
                val finished = TypingTestScore.finishedWordCount(typed)
                Text(
                    buildAnnotatedString {
                        prompt.forEachIndexed { i, w ->
                            val style = when {
                                i < finished && i < typedWords.size ->
                                    SpanStyle(color = if (TypingTestScore.same(w, typedWords[i])) good else bad)
                                i == finished -> SpanStyle(color = textColor, fontWeight = FontWeight.Bold,
                                    textDecoration = TextDecoration.Underline)
                                else -> SpanStyle(color = textColor.copy(alpha = 0.6f))
                            }
                            withStyle(style) { append(w) }
                            append(' ')
                        }
                    },
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
            }

            AndroidView(
                factory = { ctx ->
                    EditText(ctx).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        minLines = 3
                        textSize = 20f
                        setTextColor(textColor.toArgb())
                        hint = "Press Start, then type here"
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                if (!running) return
                                val now = s?.toString() ?: ""
                                if (startMs == 0L && now.isNotEmpty()) startMs = SystemClock.uptimeMillis()
                                if (now.length < typed.length) {
                                    deletions++
                                    val at = TypingTestScore.finishedWordCount(now)
                                    if (at !in corrected) corrected.add(at)
                                }
                                typed = now
                                TypingTestRecorder.emit(TypingTestRecorder.event("field")
                                    .put("text", now)
                                    .put("selStart", selectionStart)
                                    .put("selEnd", selectionEnd)
                                    .put("promptIndex", TypingTestScore.finishedWordCount(now)))
                                if (TypingTestScore.finishedWordCount(now) >= prompt.size && prompt.isNotEmpty()) {
                                    finish("completed")
                                }
                            }
                        })
                        editText.value = this
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            result?.let { r ->
                Text("Result", style = MaterialTheme.typography.titleMedium)
                Text(
                    "%.1f WPM, %.0f%% of words right\n".format(r.wpm, r.accuracy * 100) +
                        "${r.correctWords} right, ${r.wrongWords} wrong, ${r.missedWords} missed, " +
                        "${r.extraWords} extra\n" +
                        "$deletions deletions"
                )
                if (corrected.isNotEmpty()) {
                    Text("Words that needed a correction: " +
                        corrected.sorted().mapNotNull { prompt.getOrNull(it) }.joinToString(", "))
                }
                resultFile?.let { f ->
                    Text("Recording: ${f.name}", style = MaterialTheme.typography.labelMedium)
                    Button(onClick = { fileToSave = f; saveLauncher.launch(f.name) }) { Text("Save a copy") }
                }
            }

            status?.let { Text(it, style = MaterialTheme.typography.labelLarge) }

            if (!running && recordings.isNotEmpty()) {
                Text("Recordings on this phone", style = MaterialTheme.typography.titleMedium)
                recordings.take(20).forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} (${f.length() / 1024} KB)", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { fileToSave = f; saveLauncher.launch(f.name) }) { Text("Save") }
                    }
                }
            }
        }
    }
}
