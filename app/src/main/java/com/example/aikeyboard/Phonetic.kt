package com.example.aikeyboard

/**
 * Simple Avro-style phonetic Bangla: type "ami" and get "আমি".
 * Small o is silent, capital O gives the ো sign. Consecutive consonants join automatically (ক্ষ, স্ত ...).
 */
object Phonetic {
    private class Match(val cons: Boolean, val a: String, val b: String, val len: Int)

    private val CONS = mapOf(
        "kkh" to "ক্ষ", "Gg" to "জ্ঞ", "chh" to "ছ",
        "kh" to "খ", "gh" to "ঘ", "ch" to "চ", "jh" to "ঝ", "Th" to "ঠ", "Dh" to "ঢ",
        "th" to "থ", "dh" to "ধ", "ph" to "ফ", "bh" to "ভ", "sh" to "শ", "Sh" to "ষ", "Rh" to "ঢ়",
        "NG" to "ঙ", "Ng" to "ঙ",
        "k" to "ক", "g" to "গ", "c" to "চ", "j" to "জ", "J" to "জ", "T" to "ট", "D" to "ড", "N" to "ণ",
        "t" to "ত", "d" to "দ", "n" to "ন", "p" to "প", "f" to "ফ", "b" to "ব", "v" to "ভ", "m" to "ম",
        "z" to "য", "y" to "য়", "r" to "র", "R" to "ড়", "l" to "ল", "S" to "শ", "s" to "স", "h" to "হ",
        "q" to "ক", "x" to "ক্স", "w" to "ওয়"
    )

    // roman -> (independent vowel, vowel sign after a consonant)
    private val VOW = mapOf(
        "oi" to ("ঐ" to "ৈ"), "ou" to ("ঔ" to "ৌ"), "OI" to ("ঐ" to "ৈ"), "OU" to ("ঔ" to "ৌ"),
        "ii" to ("ঈ" to "ী"), "uu" to ("ঊ" to "ূ"),
        "o" to ("অ" to ""), "a" to ("আ" to "া"), "i" to ("ই" to "ি"), "I" to ("ঈ" to "ী"),
        "u" to ("উ" to "ু"), "U" to ("ঊ" to "ূ"), "e" to ("এ" to "ে"), "E" to ("এ" to "ে"), "O" to ("ও" to "ো")
    )

    private fun lookup(src: String, i: Int): Match? {
        for (len in 3 downTo 1) {
            if (i + len > src.length) continue
            val tok = src.substring(i, i + len)
            val c = CONS[tok]
            if (c != null) return Match(true, c, "", len)
            val v = VOW[tok]
            if (v != null) return Match(false, v.first, v.second, len)
        }
        return null
    }

    fun convert(src: String): String {
        val sb = StringBuilder()
        var pending = false // last thing written is a consonant that has no vowel yet
        var i = 0
        while (i < src.length) {
            if (src.startsWith("rri", i)) {
                sb.append(if (pending) "ৃ" else "ঋ")
                pending = false
                i += 3
                continue
            }
            if (src.startsWith("ng", i)) {
                val next = src.getOrNull(i + 2)
                if (next == 'k' || next == 'g') {
                    if (pending) sb.append('্')
                    sb.append("ঙ")
                    pending = true
                } else {
                    sb.append("ং")
                    pending = false
                }
                i += 2
                continue
            }
            var m = lookup(src, i)
            if (m == null && src[i].isUpperCase()) {
                m = lookup(src.substring(0, i) + src[i].lowercaseChar() + src.substring(i + 1), i)
            }
            if (m != null) {
                if (m.cons) {
                    if (pending) sb.append('্')
                    sb.append(m.a)
                    pending = true
                } else {
                    sb.append(if (pending) m.b else m.a)
                    pending = false
                }
                i += m.len
                continue
            }
            when (src[i]) {
                ':' -> sb.append("ঃ")
                '^' -> sb.append("ঁ")
                else -> sb.append(src[i])
            }
            pending = false
            i++
        }
        return sb.toString()
    }
}
