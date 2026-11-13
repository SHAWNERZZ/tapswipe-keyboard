package org.futo.inputmethod.latin.tapswipe

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * A single text field for the typing simulator to type into. See `docs/plans/TYPING_SIMULATOR.md`.
 *
 * The host starts it with a run id:
 *
 *   adb shell am start -n <package>/org.futo.inputmethod.latin.tapswipe.TypingSimTargetActivity --es id <id>
 *
 * The field carries the recording marker, so the keyboard records every touch and decision into
 * `files/recordings/<id>.jsonl`. This activity adds a `field` event on every text change, which is
 * how the host learns what was committed. Nothing else is stored.
 */
class TypingSimTargetActivity : Activity() {
    private lateinit var field: EditText
    private var runId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")?.takeIf { TypingTestRecorder.isSafeId(it) } ?: "sim"
        runId = id
        TypingTestRecorder.start(this, id)
        TypingTestRecorder.emit(TypingTestRecorder.event("sim_start").put("id", id))

        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            privateImeOptions = TypingTestRecorder.markerFor(id)
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            textSize = 20f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    TypingTestRecorder.emit(TypingTestRecorder.event("field")
                        .put("text", s?.toString() ?: "")
                        .put("selStart", selectionStart)
                        .put("selEnd", selectionEnd))
                }
            })
        }
        setContentView(FrameLayout(this).apply {
            addView(field, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }

    /**
     * The simulator sets the field's text here, to give the next typed words a context. Started with
     * FLAG_ACTIVITY_SINGLE_TOP, so the activity, its recording, and the keyboard stay as they are.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val text = intent.getStringExtra("text") ?: return
        field.setText(text)
        field.setSelection(field.length())
    }

    /** The keyboard can only be asked for once the window has focus. Earlier requests are dropped. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        field.requestFocus()
        field.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        if (TypingTestRecorder.currentId == runId) {
            TypingTestRecorder.emit(TypingTestRecorder.event("sim_end").put("text", field.text.toString()))
            TypingTestRecorder.stopIf(runId)
        }
        super.onDestroy()
    }
}
