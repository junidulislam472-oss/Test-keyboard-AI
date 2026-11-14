package com.example.aikeyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        fun label(t: String, size: Float = 15f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, dp(14), 0, dp(4))
        }

        fun field(h: String, value: String, secret: Boolean = false) = EditText(this).apply {
            setHint(h)
            setText(value)
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        fun spin(options: List<String>, selected: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
        }

        fun button(t: String, action: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { action() }
        }

        root.addView(label("AI Keyboard", 24f))
        root.addView(label("Step 1: turn the keyboard on. Step 2: switch to it. Step 3: add your key below."))
        root.addView(button("1. Enable AI Keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        root.addView(button("2. Choose AI Keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        root.addView(button("3. Allow microphone (for voice typing)") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        root.addView(label("AI provider"))
        val provider = spin(
            listOf("Gemini (Google AI Studio)", "OpenAI-compatible (OpenAI, Groq, OpenRouter...)"),
            if (prefs.getString("provider", AiClient.PROVIDER_GEMINI) == AiClient.PROVIDER_OPENAI) 1 else 0
        )
        root.addView(provider)

        root.addView(label("API key (free Gemini key: aistudio.google.com/apikey)"))
        val key = field("API key", prefs.getString("key", "") ?: "", true)
        root.addView(key)
        root.addView(label("Model name (if you get a 404 error, use a current model name from your provider)"))
        val model = field("Model", prefs.getString("model", "gemini-flash-latest") ?: "")
        root.addView(model)
        root.addView(label("Base URL (OpenAI-compatible only, e.g. https://api.groq.com/openai/v1)"))
        val base = field("Base URL", prefs.getString("base", "https://api.openai.com/v1") ?: "")
        root.addView(base)

        root.addView(label("Capital letters"))
        val cap = spin(listOf("Off", "Start of each sentence", "Every word"), prefs.getInt("cap", 1))
        root.addView(cap)

        root.addView(label("Bangla layout"))
        val bn = spin(
            listOf("Phonetic: type ami, get আমি (small o is silent, capital O gives ো)", "Key-by-key: Bangla letters on the keys"),
            prefs.getInt("bnlayout", 0)
        )
        root.addView(bn)

        root.addView(label("Voice typing engine"))
        val stt = spin(
            listOf("Gemini AI: best for Bangla, English and mixed (needs Gemini key)", "Android built-in (free, follows keyboard language)"),
            prefs.getInt("stt", 0)
        )
        root.addView(stt)

        root.addView(label("GIFs and stickers: free GIPHY key from developers.giphy.com"))
        val giphy = field("GIPHY API key", prefs.getString("giphy", "") ?: "", true)
        root.addView(giphy)

        fun save() {
            prefs.edit()
                .putString("provider", if (provider.selectedItemPosition == 1) AiClient.PROVIDER_OPENAI else AiClient.PROVIDER_GEMINI)
                .putString("key", key.text.toString().trim())
                .putString("model", model.text.toString().trim())
                .putString("base", base.text.toString().trim())
                .putInt("cap", cap.selectedItemPosition)
                .putInt("bnlayout", bn.selectedItemPosition)
                .putInt("stt", stt.selectedItemPosition)
                .putString("giphy", giphy.text.toString().trim())
                .apply()
        }

        root.addView(button("Save") { save() })
        root.addView(button("Save and test AI connection") {
            save()
            val cfg = AiClient.load(this@SettingsActivity)
            Thread {
                val msg = try {
                    AiClient.improve(cfg, "i is go to school tomorow", "Fix spelling, grammar and punctuation only.")
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                runOnUiThread {
                    AlertDialog.Builder(this@SettingsActivity).setMessage(msg).setPositiveButton("OK", null).show()
                }
            }.start()
        })

        root.addView(label("Try it here:"))
        root.addView(EditText(this).apply {
            setHint("Type a sentence, then tap ✨ Improve")
            minLines = 3
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
