package com.example.aikeyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.util.concurrent.Executors

class AiKeyboardService : InputMethodService() {

    companion object {
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

        private val BG = Color.parseColor("#1C1C1E")
        private val KEY = Color.parseColor("#3A3A3C")
        private val SPECIAL = Color.parseColor("#2C2C2E")
        private val CARD = Color.parseColor("#2C2C2E")
        private val ACCENT = Color.parseColor("#6C47FF")
        private val TEAL = Color.parseColor("#00A38C")
        private val RED = Color.parseColor("#E5484D")
        private val PRESSED = Color.parseColor("#636366")

        private val MODES = listOf(
            "Fix" to "Fix spelling, grammar and punctuation only. Keep the original wording and tone as much as possible.",
            "Polish" to "Improve clarity, flow and word choice. Keep the same meaning and tone.",
            "Formal" to "Rewrite in a polite, professional tone.",
            "Casual" to "Rewrite in a friendly, natural chat tone.",
            "Short" to "Make it shorter and more direct without losing the key information."
        )

        private val PROMPTS = listOf(
            "Polite" to "Rewrite politely and respectfully.",
            "Professional" to "Rewrite in a professional business tone.",
            "→ Bangla" to "Translate to natural Bangla (Bengali script).",
            "→ English" to "Translate to natural English.",
            "Shorter" to "Make it much shorter.",
            "Friendly" to "Make it warm and friendly.",
            "Add emoji" to "Add fitting emoji but keep the text the same.",
            "Reply" to "Write a good reply to the copied message or to this text."
        )

        private val GIF_QUERIES = listOf("Happy", "Love", "Funny", "Thanks", "Sad", "Wow", "Good morning", "Birthday", "OK")

        // 0 = English / phonetic letters, 1 = symbols, 2 = Bangla consonants, 3 = Bangla vowels
        private val PAGES = listOf(
            listOf("1 2 3 4 5 6 7 8 9 0", "q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
            listOf("@ # \$ % & - + ( )", "* \" ' : ; ! ? / _", ", . । = < > [ ] { }", "~ ` | \\ ^ € £ ¥ ° •"),
            listOf("ক খ গ ঘ ঙ চ ছ জ ঝ ঞ", "ট ঠ ড ঢ ণ ত থ দ ধ ন", "প ফ ব ভ ম য র ল শ ষ", "স হ ড় ঢ় য় ৎ ং ঃ ঁ ্"),
            listOf("অ আ ই ঈ উ ঊ ঋ এ ঐ ও", "ঔ া ি ী ু ূ ৃ ে ৈ ো", "ৌ । , . ? ! ' \" -", "১ ২ ৩ ৪ ৫ ৬ ৭ ৮ ৯ ০")
        )
    }

    private class Target(val selected: String, val before: String, val after: String) {
        val original: String get() = if (selected.isNotEmpty()) selected else before + after
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(4)

    // views
    private lateinit var topSlot: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var keysBox: LinearLayout
    private lateinit var improveKey: TextView
    private lateinit var modeKey: TextView
    private lateinit var micKey: TextView
    private var shiftKey: TextView? = null
    private val letterKeys = ArrayList<Pair<TextView, String>>()

    // settings
    private var capMode = 1      // 0 off, 1 sentences, 2 every word
    private var bnLayout = 0     // 0 phonetic, 1 key-by-key
    private var sttEngine = 0    // 0 Gemini AI, 1 Android built-in
    private var mode = 0

    // typing state
    private var lang = 0         // 0 English, 1 Bangla
    private var page = 0
    private var shiftState = 0   // 0 off, 1 once, 2 lock
    private var autoCap = false
    private var lastShiftTap = 0L
    private val roman = StringBuilder()

    // inline input (AI prompt box / GIF search box)
    private var localMode = 0    // 0 none, 1 AI prompt, 2 GIF search
    private val localText = StringBuilder()
    private var localView: TextView? = null
    private var useClipCtx = false

    // AI state
    private var busy = false
    private var lastOriginal = ""
    private var lastImproved = ""

    // media panel state
    private var mediaTab = 0
    private var giphyQuery = ""

    // mic state
    private var micState = 0     // 0 idle, 1 listening, 2 transcribing
    private var usingAi = false
    private var rec: MediaRecorder? = null
    private var recFile: File? = null
    private var recognizer: SpeechRecognizer? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClip() }

    private val repeatDelete = object : Runnable {
        override fun run() {
            backspace()
            main.postDelayed(this, 60)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        val old = System.currentTimeMillis() - 3600_000
        File(cacheDir, "gifs").listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        cancelMic()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        thumbs.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        loadSettings()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        topSlot = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(topSlot, MATCH, WRAP)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        improveKey = key("✨ Improve", 1f, ACCENT, 112) { improve() }
        modeKey = key(MODES[mode].first, 1f, SPECIAL, 64) { cycleMode() }
        micKey = key("🎤", 1f, SPECIAL, 52) { toggleMic() }
        bar.addView(improveKey)
        bar.addView(modeKey)
        bar.addView(key("💡", 1f, SPECIAL, 52) { showOptions() })
        bar.addView(key("✍", 1f, TEAL, 52) { startLocal(1) })
        bar.addView(micKey)
        bar.addView(key("📋", 1f, SPECIAL, 52) { showClipboard() })
        bar.addView(key("😊", 1f, SPECIAL, 52) { mediaTab = 0; showMedia() })
        bar.addView(key("↩", 1f, SPECIAL, 52) { undo() })
        bar.addView(key("⚙", 1f, SPECIAL, 52) { openSettings() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }, MATCH, WRAP)

        content = FrameLayout(this).apply { minimumHeight = dp(240) }
        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(keysBox, MATCH, WRAP)
        root.addView(content, MATCH, WRAP)

        page = lettersPage()
        render()
        refreshCaps()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::keysBox.isInitialized) return
        loadSettings()
        modeKey.text = MODES[mode].first
        roman.setLength(0)
        if (!restarting) {
            val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else lettersPage()
            shiftState = 0
            localMode = 0
            localView = null
            localText.setLength(0)
            showKeys()
        }
        render()
        refreshCaps()
        refreshChip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        roman.setLength(0)
        cancelMic()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (roman.isNotEmpty() && localMode == 0 && candidatesStart == -1 && candidatesEnd == -1) roman.setLength(0)
        if (::keysBox.isInitialized) refreshCaps()
    }

    private fun loadSettings() {
        val p = Prefs.get(this)
        capMode = p.getInt("cap", 1)
        bnLayout = p.getInt("bnlayout", 0)
        sttEngine = p.getInt("stt", 0)
        mode = p.getInt("mode", 0).coerceIn(0, MODES.size - 1)
        lang = p.getInt("lang", 0)
    }

    private fun lettersPage() = if (lang == 1 && bnLayout == 1) 2 else 0

    // ---------------------------------------------------------------- typing

    private fun out(s: String) {
        if (localMode != 0) {
            localText.append(s)
            updateLocal()
        } else {
            currentInputConnection?.commitText(s, 1)
        }
    }

    private fun typeToken(base: String) {
        if (page == 0 && base[0] in 'a'..'z') {
            val ch = if (upper()) base.uppercase() else base
            if (shiftState == 1) shiftState = 0
            if (lang == 1) {
                roman.append(ch)
                showComposing()
            } else {
                out(ch)
            }
        } else {
            finishWord()
            out(base)
        }
        refreshCaps()
    }

    private fun showComposing() {
        if (localMode != 0) updateLocal()
        else currentInputConnection?.setComposingText(Phonetic.convert(roman.toString()), 1)
    }

    private fun finishWord() {
        if (roman.isEmpty()) return
        val converted = Phonetic.convert(roman.toString())
        roman.setLength(0)
        if (localMode != 0) {
            localText.append(converted)
            updateLocal()
        } else {
            currentInputConnection?.commitText(converted, 1)
        }
    }

    private fun backspace() {
        if (roman.isNotEmpty()) {
            roman.setLength(roman.length - 1)
            if (localMode != 0) updateLocal()
            else if (roman.isEmpty()) currentInputConnection?.commitText("", 1)
            else showComposing()
            return
        }
        if (localMode != 0) {
            if (localText.isNotEmpty()) {
                localText.setLength(localText.length - 1)
                updateLocal()
            }
            return
        }
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        refreshCaps()
    }

    private fun enter() {
        finishWord()
        if (localMode != 0) {
            runLocal()
            return
        }
        val ic = currentInputConnection ?: return
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        refreshCaps()
    }

    private fun toggleLang() {
        finishWord()
        lang = 1 - lang
        Prefs.get(this).edit().putInt("lang", lang).apply()
        page = lettersPage()
        shiftState = 0
        render()
        refreshCaps()
    }

    private fun switchKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ---------------------------------------------------------------- capitalization

    private fun upper() = shiftState > 0 || autoCap

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == 2 -> 0
            now - lastShiftTap < 350 -> 2
            shiftState == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
        updateLetterLabels()
    }

    private fun refreshCaps() {
        autoCap = needsAutoCap()
        updateLetterLabels()
    }

    private fun updateLetterLabels() {
        val up = upper()
        for ((tv, base) in letterKeys) tv.text = if (up) base.uppercase() else base
        shiftKey?.text = when {
            shiftState == 2 -> "⇪"
            shiftState == 1 || autoCap -> "⬆"
            else -> "⇧"
        }
    }

    private fun needsAutoCap(): Boolean {
        if (capMode == 0 || lang != 0 || localMode != 0) return false
        val info = currentInputEditorInfo
        if (info != null) {
            if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
            val v = info.inputType and InputType.TYPE_MASK_VARIATION
            if (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                v == InputType.TYPE_TEXT_VARIATION_URI || v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) return false
        }
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(8, 0)?.toString() ?: return false
        if (before.isEmpty()) return true
        val last = before.last()
        if (capMode == 2) return last.isWhitespace()
        if (last == '\n') return true
        if (last.isWhitespace()) {
            val t = before.trimEnd()
            return t.isEmpty() || t.last() in ".!?।"
        }
        return false
    }

    // ---------------------------------------------------------------- AI actions

    private fun readTarget(ic: InputConnection): Target {
        val sel = ic.getSelectedText(0)?.toString().orEmpty()
        if (sel.isNotEmpty()) return Target(sel, "", "")
        return Target(
            "",
            ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty(),
            ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        )
    }

    private fun prepTarget(allowEmpty: Boolean = false): Target? {
        val ic = currentInputConnection ?: return null
        if (isSensitive(currentInputEditorInfo)) {
            toast("AI is off in password and incognito fields")
            return null
        }
        val t = readTarget(ic)
        if (!allowEmpty && t.original.isBlank()) {
            toast("Type some text first")
            return null
        }
        return t
    }

    private fun replaceTarget(t: Target, newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (t.selected.isEmpty()) ic.deleteSurroundingText(t.before.length, t.after.length)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
        lastOriginal = t.original
        lastImproved = newText
        refreshCaps()
    }

    private fun <T : Any> runAi(task: () -> T, onError: () -> Unit = {}, onResult: (T) -> Unit) {
        if (busy) {
            toast("Please wait, AI is still working")
            onError()
            return
        }
        busy = true
        improveKey.text = "⏳ Working…"
        io.execute {
            var result: T? = null
            var error: String? = null
            try {
                result = task()
            } catch (e: Exception) {
                error = e.message ?: "AI error"
            }
            val r = result
            val err = error
            main.post {
                busy = false
                improveKey.text = "✨ Improve"
                if (err != null) {
                    toast(err)
                    onError()
                } else if (r != null) {
                    onResult(r)
                }
            }
        }
    }

    private fun improve() {
        finishWord()
        val t = prepTarget() ?: return
        val cfg = AiClient.load(this)
        val instruction = MODES[mode].second
        runAi({ AiClient.improve(cfg, t.original, instruction) }) { replaceTarget(t, it) }
    }

    private fun runPrompt(command: String) {
        finishWord()
        val t = prepTarget(allowEmpty = true) ?: return
        val cfg = AiClient.load(this)
        val ctx = if (useClipCtx) ClipStore.items.firstOrNull { it.text != null }?.text else null
        endLocal()
        runAi({ AiClient.custom(cfg, t.original, command, ctx) }) { replaceTarget(t, it) }
    }

    private fun showOptions() {
        finishWord()
        val t = prepTarget() ?: return
        val cfg = AiClient.load(this)
        showPanel(msgView("🔍 Analysing your text…"), true)
        runAi({ AiClient.options(cfg, t.original) }, { showKeys() }) { opts -> showOptionCards(t, opts) }
    }

    private fun showOptionCards(t: Target, opts: List<String>) {
        val labels = listOf("✅ Fix", "👍 Better", "🏆 Best")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(header("💡 Pick a version"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        opts.forEachIndexed { i, opt ->
            val c = card()
            c.addView(TextView(this).apply {
                text = labels.getOrElse(i) { "Option ${i + 1}" }
                setTextColor(Color.parseColor("#B79CFF"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            c.addView(TextView(this).apply {
                text = opt
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            c.setOnClickListener {
                replaceTarget(t, opt)
                showKeys()
            }
            list.addView(c)
        }
        box.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        showPanel(box, true)
    }

    private fun undo() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(lastImproved.length, 0)?.toString()
        if (lastImproved.isNotEmpty() && before == lastImproved) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(lastImproved.length, 0)
            ic.commitText(lastOriginal, 1)
            ic.endBatchEdit()
            lastImproved = ""
            refreshCaps()
        } else {
            toast("Nothing to undo")
        }
    }

    private fun cycleMode() {
        mode = (mode + 1) % MODES.size
        Prefs.get(this).edit().putInt("mode", mode).apply()
        modeKey.text = MODES[mode].first
    }

    /** Never send password fields or incognito fields to an AI. */
    private fun isSensitive(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val pwText = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val pwNum = cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return pwText || pwNum || incognito
    }

    // ---------------------------------------------------------------- inline input (AI prompt / GIF search)

    private fun startLocal(m: Int) {
        finishWord()
        if (m == 1 && isSensitive(currentInputEditorInfo)) {
            toast("AI is off in password and incognito fields")
            return
        }
        localMode = m
        localText.setLength(0)
        showKeys()
        topSlot.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (m == 1) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            lateinit var ctxChip: TextView
            ctxChip = key("📋 Use copied text", 1f, if (useClipCtx) ACCENT else SPECIAL, 140) {
                useClipCtx = !useClipCtx
                ctxChip.background = keyBackground(if (useClipCtx) ACCENT else SPECIAL)
            }
            chips.addView(ctxChip)
            for ((label, instruction) in PROMPTS) {
                chips.addView(key(label, 1f, SPECIAL, maxOf(64, 24 + label.length * 9)) { runPrompt(instruction) })
            }
            col.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            hint = if (m == 1) "Tell AI what to do with your text…" else "Search GIFs…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
            background = shape(CARD, 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        localView = tv
        row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) })
        row.addView(key("Go ▶", 1f, ACCENT, 64) { runLocal() })
        row.addView(key("✕", 1f, SPECIAL, 44) {
            endLocal()
            if (m == 2) showMedia()
        })
        col.addView(row)
        topSlot.addView(col, MATCH, WRAP)
        topSlot.visibility = View.VISIBLE
    }

    private fun updateLocal() {
        val pending = if (roman.isNotEmpty()) Phonetic.convert(roman.toString()) else ""
        localView?.text = localText.toString() + pending
    }

    private fun runLocal() {
        finishWord()
        val text = localText.toString().trim()
        if (text.isEmpty()) {
            toast("Type something first")
            return
        }
        if (localMode == 1) {
            runPrompt(text)
        } else {
            endLocal()
            giphyQuery = text
            showMedia()
        }
    }

    private fun endLocal() {
        localMode = 0
        localView = null
        localText.setLength(0)
        if (::topSlot.isInitialized) {
            topSlot.removeAllViews()
            topSlot.visibility = View.GONE
        }
        refreshChip()
    }

    // ---------------------------------------------------------------- clipboard

    private fun readClip() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (e: Exception) { null }
        if (clip == null || clip.itemCount == 0) return
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= 26 && desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && desc.hasMimeType("image/*")) {
            val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") } ?: "image/jpeg"
            io.execute {
                try {
                    val dir = File(cacheDir, "clips").apply { mkdirs() }
                    val ext = when {
                        mime.contains("png") -> "png"
                        mime.contains("gif") -> "gif"
                        mime.contains("webp") -> "webp"
                        else -> "jpg"
                    }
                    val f = File(dir, "clip_${System.currentTimeMillis()}.$ext")
                    contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    if (f.length() > 0) main.post {
                        ClipStore.add(ClipItem(null, f, mime, System.currentTimeMillis()))
                        refreshChip()
                    }
                } catch (e: Exception) {
                    // clip could not be read, ignore
                }
            }
            return
        }
        val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            ClipStore.add(ClipItem(text.take(10000), null, null, System.currentTimeMillis()))
            refreshChip()
        }
    }

    private fun refreshChip() {
        if (!::topSlot.isInitialized || localMode != 0) return
        topSlot.removeAllViews()
        val c = ClipStore.items.firstOrNull()
        val fresh = c != null && !c.dismissed && System.currentTimeMillis() - c.time < 5 * 60_000 &&
            !isSensitive(currentInputEditorInfo)
        if (!fresh || c == null) {
            topSlot.visibility = View.GONE
            return
        }
        val label = c.text?.let { "📋  " + it.replace("\n", " ") } ?: "🖼  Copied image · tap to paste"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chip = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = shape(CARD, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { pasteClip(c) }
        }
        row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(4)) })
        row.addView(key("✕", 1f, SPECIAL, 40) {
            c.dismissed = true
            refreshChip()
        })
        topSlot.addView(row, MATCH, WRAP)
        topSlot.visibility = View.VISIBLE
    }

    private fun pasteClip(item: ClipItem) {
        item.dismissed = true
        if (item.text != null) {
            finishWord()
            out(item.text)
            refreshCaps()
        } else if (item.file != null && item.mime != null) {
            sendContent(item.file, item.mime)
        }
        refreshChip()
    }

    private fun showClipboard() {
        finishWord()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(header("📋 Clipboard", key("Clear", 1f, SPECIAL, 64) {
            ClipStore.clear()
            showClipboard()
            refreshChip()
        }))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (ClipStore.items.isEmpty()) {
            list.addView(msgView("Nothing copied yet.\nCopy text or an image and it will show up here."))
        }
        for (item in ClipStore.items.toList()) {
            val c = card()
            if (item.text != null) {
                c.addView(TextView(this).apply {
                    text = item.text
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
            } else if (item.file != null) {
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
                val path = item.file.path
                loadInto(iv) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                c.addView(iv, LinearLayout.LayoutParams(MATCH, dp(110)))
            }
            c.setOnClickListener {
                pasteClip(item)
                showKeys()
            }
            list.addView(c)
        }
        box.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        showPanel(box, true)
    }

    /** Sends an image or GIF into the app (WhatsApp, Telegram, Messages ... if the app allows it). */
    private fun sendContent(file: File, mime: String) {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (ic == null || info == null) return
        val accepted = EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
        if (!accepted) {
            toast("This app does not accept images or GIFs from keyboards")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val content = InputContentInfoCompat(uri, ClipDescription(file.name, arrayOf(mime)), null)
        val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        if (!InputConnectionCompat.commitContent(ic, info, content, flags, null)) toast("The app rejected it")
    }

    // ---------------------------------------------------------------- emoji / GIF / stickers

    private fun showMedia() {
        finishWord()
        val cats = EmojiData.CATEGORIES
        val gifTab = cats.size
        val stickerTab = cats.size + 1
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cats.forEachIndexed { i, c ->
            tabs.addView(key(c.first, 1f, if (mediaTab == i) ACCENT else SPECIAL, 46) {
                mediaTab = i
                showMedia()
            })
        }
        tabs.addView(key("GIF", 1f, if (mediaTab == gifTab) ACCENT else SPECIAL, 52) {
            mediaTab = gifTab
            giphyQuery = ""
            showMedia()
        })
        tabs.addView(key("Sticker", 1f, if (mediaTab == stickerTab) ACCENT else SPECIAL, 72) {
            mediaTab = stickerTab
            giphyQuery = ""
            showMedia()
        })
        tabs.addView(key("⌨", 1f, SPECIAL, 48) { showKeys() })
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        })
        if (mediaTab < cats.size) {
            box.addView(emojiGrid(cats[mediaTab].second), LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            giphyView(box, mediaTab == stickerTab)
        }
        showPanel(box, true)
    }

    private fun emojiGrid(all: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        all.split(" ").filter { it.isNotEmpty() }.chunked(8).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { e -> row.addView(emojiCell(e)) }
            repeat(8 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f)) }
            col.addView(row)
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun emojiCell(e: String) = TextView(this).apply {
        text = e
        textSize = 24f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            finishWord()
            out(e)
        }
    }

    private fun giphyView(box: LinearLayout, sticker: Boolean) {
        val apiKey = Prefs.get(this).getString("giphy", "")?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            box.addView(msgView("To use GIFs and stickers, get a free GIPHY API key (developers.giphy.com) and paste it in the AI Keyboard app."))
            return
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(key("🔍", 1f, TEAL, 48) { startLocal(2) })
        chips.addView(key("Trending", 1f, if (giphyQuery.isEmpty()) ACCENT else SPECIAL, 84) {
            giphyQuery = ""
            showMedia()
        })
        for (q in GIF_QUERIES) {
            chips.addView(key(q, 1f, if (giphyQuery == q) ACCENT else SPECIAL, maxOf(56, 24 + q.length * 9)) {
                giphyQuery = q
                showMedia()
            })
        }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(msgView("Loading…"))
        box.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val q = giphyQuery
        val tab = mediaTab
        thumbs.execute {
            var items: List<GiphyClient.Item>? = null
            var error = ""
            try {
                items = GiphyClient.fetch(apiKey, sticker, q)
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
            val list = items
            val err = error
            main.post {
                if (mediaTab != tab || giphyQuery != q) return@post
                results.removeAllViews()
                if (list == null) {
                    results.addView(msgView("Could not load: $err\nCheck your GIPHY key in the AI Keyboard app."))
                    return@post
                }
                if (list.isEmpty()) results.addView(msgView("No results"))
                list.chunked(3).forEach { chunk ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    chunk.forEach { item -> row.addView(gifCell(item)) }
                    repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(84), 1f)) }
                    results.addView(row)
                }
            }
        }
    }

    private fun gifCell(item: GiphyClient.Item): View {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(CARD)
            layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
            setOnClickListener { sendGif(item) }
        }
        loadInto(iv) {
            val b = GiphyClient.download(item.thumbUrl)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }
        return iv
    }

    private fun sendGif(item: GiphyClient.Item) {
        toast("Sending…", false)
        thumbs.execute {
            try {
                val bytes = GiphyClient.download(item.gifUrl)
                val dir = File(cacheDir, "gifs").apply { mkdirs() }
                val f = File(dir, "g_${System.currentTimeMillis()}.gif")
                f.writeBytes(bytes)
                main.post { sendContent(f, "image/gif") }
            } catch (e: Exception) {
                main.post { toast("Download failed") }
            }
        }
    }

    private fun loadInto(iv: ImageView, loader: () -> Bitmap?) {
        thumbs.execute {
            val bmp = try { loader() } catch (e: Exception) { null }
            if (bmp != null) main.post { iv.setImageBitmap(bmp) }
        }
    }

    // ---------------------------------------------------------------- speech to text

    private fun updateMic() {
        micKey.text = when (micState) {
            1 -> "⏹"
            2 -> "⏳"
            else -> "🎤"
        }
        micKey.background = keyBackground(if (micState == 1) RED else SPECIAL)
    }

    private fun toggleMic() {
        finishWord()
        when (micState) {
            1 -> if (usingAi) stopRecording() else recognizer?.stopListening()
            2 -> toast("Transcribing…", false)
            else -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    toast("Allow the microphone, then tap 🎤 again")
                    return
                }
                val cfg = AiClient.load(this)
                usingAi = sttEngine == 0 && cfg.provider == AiClient.PROVIDER_GEMINI && cfg.apiKey.isNotEmpty()
                if (usingAi) startRecording() else startAndroidSpeech()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun startRecording() {
        val f = File(cacheDir, "voice.aac")
        recFile = f
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setMaxDuration(90_000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { stopRecording() }
            }
            r.prepare()
            r.start()
            rec = r
            micState = 1
            updateMic()
        } catch (e: Exception) {
            r.release()
            toast("Microphone error: ${e.message}")
        }
    }

    private fun stopRecording() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (e: Exception) { /* recording too short */ }
        r.release()
        micState = 2
        updateMic()
        val f = recFile ?: return
        val cfg = AiClient.load(this)
        io.execute {
            var text: String? = null
            var error: String? = null
            try {
                text = AiClient.transcribe(cfg, f.readBytes())
            } catch (e: Exception) {
                error = e.message ?: "Speech error"
            }
            val t = text
            val err = error
            main.post {
                micState = 0
                updateMic()
                if (err != null) toast(err)
                else if (!t.isNullOrBlank()) insertSpoken(t)
            }
        }
    }

    private fun startAndroidSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this phone")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                endAndroidSpeech()
                currentInputConnection?.finishComposingText()
                toast("Did not catch that, try again", false)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrEmpty() && localMode == 0) currentInputConnection?.setComposingText(t, 1)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                endAndroidSpeech()
                if (!t.isNullOrBlank()) insertSpoken(t)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == 1) "bn-BD" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        micState = 1
        updateMic()
        r.startListening(i)
    }

    private fun endAndroidSpeech() {
        micState = 0
        if (::micKey.isInitialized) updateMic()
        val r = recognizer
        recognizer = null
        if (r != null) main.post { r.destroy() }
    }

    private fun cancelMic() {
        rec?.let {
            try { it.stop() } catch (e: Exception) { /* ignore */ }
            it.release()
        }
        rec = null
        recognizer?.destroy()
        recognizer = null
        micState = 0
        if (::micKey.isInitialized) updateMic()
    }

    private fun insertSpoken(text: String) {
        out(text.trim() + " ")
        refreshCaps()
    }

    // ---------------------------------------------------------------- UI building

    private fun showPanel(v: View, fixedHeight: Boolean) {
        content.removeAllViews()
        content.addView(v, MATCH, if (fixedHeight) dp(240) else WRAP)
    }

    private fun showKeys() {
        showPanel(keysBox, false)
        refreshCaps()
    }

    private fun header(title: String, vararg extra: View): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        for (e in extra) h.addView(e)
        h.addView(key("⌨", 1f, SPECIAL, 56) { showKeys() })
        return h
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.setMargins(dp(4), dp(3), dp(4), dp(3)) }
    }

    private fun msgView(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.LTGRAY)
        gravity = Gravity.CENTER
        textSize = 15f
        setPadding(dp(16), dp(28), dp(16), dp(16))
    }

    private fun render() {
        keysBox.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val screenW = resources.displayMetrics.widthPixels

        for (row in PAGES[page]) {
            val labels = row.split(" ")
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val side = screenW * (10 - labels.size).coerceAtLeast(0) / 20
                setPadding(side, 0, side, 0)
            }
            for (label in labels) {
                val k = key(display(label)) { typeToken(label) }
                if (page == 0 && label[0] in 'a'..'z') letterKeys.add(k to label)
                line.addView(k)
            }
            keysBox.addView(line, MATCH, WRAP)
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when (page) {
            0 -> {
                val s = key("⇧", 1.2f, SPECIAL) { onShiftTap() }
                shiftKey = s
                bottom.addView(s)
            }
            1 -> bottom.addView(key("ABC", 1.2f, SPECIAL) { page = lettersPage(); render(); refreshCaps() })
            2 -> bottom.addView(key("অ", 1.2f, SPECIAL) { page = 3; render() })
            else -> bottom.addView(key("ক", 1.2f, SPECIAL) { page = 2; render() })
        }
        bottom.addView(key("123", 1.1f, SPECIAL) { page = 1; render() })
        bottom.addView(key(if (lang == 0) "EN" else "বাং", 1.1f, SPECIAL, 0, { switchKeyboard() }) { toggleLang() })
        bottom.addView(key(",", 0.8f) { typeToken(",") })
        bottom.addView(key("space", 3f) {
            finishWord()
            out(" ")
            refreshCaps()
        })
        val dot = if (lang == 1 && bnLayout == 0) "।" else "."
        bottom.addView(key(dot, 0.8f) { typeToken(dot) })

        val del = key("⌫", 1.2f, SPECIAL) { }
        del.setOnClickListener(null)
        del.isClickable = true
        del.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    backspace()
                    main.postDelayed(repeatDelete, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(repeatDelete)
            }
            false
        }
        bottom.addView(del)
        bottom.addView(key("⏎", 1.2f, SPECIAL) { enter() })
        keysBox.addView(bottom, MATCH, WRAP)
        updateLetterLabels()
    }

    /** Combining marks look odd alone, so show them on a dotted circle. */
    private fun display(label: String): String {
        val t = Character.getType(label[0])
        val mark = t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
        return if (mark) "\u25CC$label" else label
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        bg: Int = KEY,
        widthDp: Int = 0,
        onLong: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 17f
            setTextColor(Color.WHITE)
            background = keyBackground(bg)
            isClickable = true
            val lp = if (widthDp > 0) LinearLayout.LayoutParams(dp(widthDp), dp(42))
            else LinearLayout.LayoutParams(0, dp(42), weight)
            lp.setMargins(dp(2), dp(3), dp(2), dp(3))
            layoutParams = lp
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }
    }

    private fun keyBackground(color: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(PRESSED, 6))
        addState(intArrayOf(), shape(color, 6))
    }

    private fun shape(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String, long: Boolean = true) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
