package com.example.aikeyboard

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("ai_keyboard", Context.MODE_PRIVATE)
}

data class AiConfig(val provider: String, val apiKey: String, val model: String, val baseUrl: String)

object AiClient {
    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI = "openai"

    fun load(ctx: Context): AiConfig {
        val p = Prefs.get(ctx)
        return AiConfig(
            p.getString("provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI,
            (p.getString("key", "") ?: "").trim(),
            (p.getString("model", "gemini-flash-latest") ?: "").trim(),
            (p.getString("base", "https://api.openai.com/v1") ?: "").trim().trimEnd('/')
        )
    }

    private const val BASE = "You are a writing assistant inside a phone keyboard. "
    private const val SAME_LANG =
        "Keep the SAME language (Bangla stays Bangla, English stays English, mixed text stays mixed) and the same meaning. "

    /** One-tap rewrite. */
    fun improve(cfg: AiConfig, text: String, instruction: String): String =
        generate(
            cfg,
            BASE + "Rewrite the user's text as instructed. " + SAME_LANG +
                "Do not add explanations, quotes or labels. Output only the rewritten text. Task: $instruction",
            text
        )

    /** The user's own instruction ("make it polite", "reply yes", ...). Text may be empty. */
    fun custom(cfg: AiConfig, text: String, command: String, context: String?): String {
        val system = BASE +
            "The user gives an instruction and the text they typed. Apply the instruction to the text and output " +
            "ONLY the final text to send, with no quotes or explanations. Keep the text's language unless the " +
            "instruction asks to translate. If the text is empty, write new text that follows the instruction."
        val user = buildString {
            if (!context.isNullOrBlank()) append("Copied message for context:\n").append(context).append("\n\n")
            append("Instruction: ").append(command).append("\n\n")
            if (text.isBlank()) append("Text: (empty)") else append("Text:\n").append(text)
        }
        return generate(cfg, system, user)
    }

    /** Three versions: Fix, Better, Best. */
    fun options(cfg: AiConfig, text: String): List<String> {
        val system = BASE +
            "Give exactly 3 rewrites of the user's text, in this order: (1) Fix: only spelling, grammar and " +
            "punctuation fixed; (2) Better: clearer and more natural; (3) Best: polished, confident and professional. " +
            SAME_LANG + "Return ONLY a JSON array of 3 strings and nothing else."
        val raw = generate(cfg, system, text, json = true)
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0 || e <= s) throw RuntimeException("AI returned an unexpected answer, try again")
        val arr = JSONArray(raw.substring(s, e + 1))
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        if (out.isEmpty()) throw RuntimeException("AI returned no suggestions")
        return out
    }

    /** Speech to text with Gemini (Bangla, English or mixed). Audio is AAC (ADTS). */
    fun transcribe(cfg: AiConfig, audio: ByteArray): String {
        requireKey(cfg)
        if (cfg.provider != PROVIDER_GEMINI) throw IllegalStateException("AI speech needs the Gemini provider")
        val prompt = "Transcribe this speech exactly. The speaker may use Bangla, English or both: write Bangla in " +
            "Bangla script and English in English. Add natural punctuation. Output only the transcript."
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/aac")
                .put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return gemini(cfg, body)
    }

    private fun requireKey(cfg: AiConfig) {
        if (cfg.apiKey.isEmpty()) throw IllegalStateException("Open the AI Keyboard app and add your API key")
    }

    private fun generate(cfg: AiConfig, system: String, user: String, json: Boolean = false): String {
        requireKey(cfg)
        if (cfg.provider != PROVIDER_GEMINI) return openAi(cfg, system, user)
        val gen = JSONObject().put("temperature", 0.3)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", gen)
        return gemini(cfg, body)
    }

    private fun gemini(cfg: AiConfig, body: JSONObject): String {
        val res = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent",
            mapOf("x-goog-api-key" to cfg.apiKey), body
        )
        val candidates = res.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw RuntimeException("Empty or blocked response")
        val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought", false)) sb.append(part.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun openAi(cfg: AiConfig, system: String, user: String): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", cfg.model).put("messages", msgs)
        val res = post("${cfg.baseUrl}/chat/completions", mapOf("Authorization" to "Bearer ${cfg.apiKey}"), body)
        return res.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(raw).getJSONObject("error").getString("message") } catch (e: Exception) { raw.take(200) }
                throw RuntimeException("HTTP $code: $msg")
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }
}
