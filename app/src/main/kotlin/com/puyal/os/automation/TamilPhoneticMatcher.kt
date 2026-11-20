package com.puyal.os.automation

import java.text.Normalizer
import kotlin.math.max

object TamilPhoneticMatcher {
    private val consMap = mapOf(
        'க' to "k", 'ங' to "ng", 'ச' to "s", 'ஞ' to "gn", 'ட' to "d", 'ண' to "n",
        'த' to "th", 'ந' to "n", 'ன' to "n", 'ப' to "b", 'ம' to "m", 'ய' to "y",
        'ர' to "r", 'ற' to "r", 'ல' to "l", 'ள' to "l", 'ழ' to "l", 'வ' to "v",
        'ஜ' to "j", 'ஷ' to "sh", 'ஸ' to "s", 'ஹ' to "h"
    )
    private val vowelMap = mapOf(
        'அ' to "a", 'ஆ' to "a", 'இ' to "i", 'ஈ' to "i", 'உ' to "u", 'ஊ' to "u",
        'எ' to "e", 'ஏ' to "e", 'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "o", 'ஔ' to "au"
    )
    private val signMap = mapOf(
        'ா' to "a", 'ி' to "i", 'ீ' to "i", 'ு' to "u", 'ூ' to "u",
        'ெ' to "e", 'ே' to "e", 'ை' to "ai", 'ொ' to "o", 'ோ' to "o", 'ௌ' to "au", '்' to ""
    )

    fun transliterateTamilToLatin(input: String): String {
        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        val sb = StringBuilder()
        var i = 0
        while (i < nfc.length) {
            val ch = nfc[i]
            when {
                ch in vowelMap -> sb.append(vowelMap[ch])
                ch in consMap -> {
                    sb.append(consMap[ch])
                    val next = if (i + 1 < nfc.length) nfc[i + 1] else '\u0000'
                    if (next in signMap) {
                        sb.append(signMap[next])
                        i++
                    } else {
                        sb.append("a")
                    }
                }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                ch.isWhitespace() -> sb.append(' ')
            }
            i++
        }
        return foldPhonetics(sb.toString())
    }

    private fun foldPhonetics(s: String): String {
        return s.lowercase()
            .replace("p", "b")
            .replace("g", "k")
            .replace("t", "d")
            .replace("w", "v")
            .replace("ch", "s")
            .replace("sh", "s")
            .replace(Regex("([a-z])\\1+"), "$1")
            .trim()
    }

    fun bestMatchScore(query: String, candidate: String): Double {
        val q = transliterateTamilToLatin(query)
        val c = transliterateTamilToLatin(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        val fullSim = similarity(q, c)
        val tokSim = c.split(" ").filter { it.isNotEmpty() }.maxOfOrNull { similarity(q, it) } ?: 0.0
        val bonus = if (c.startsWith(q) || q.startsWith(c)) 0.10 else 0.0
        return (max(fullSim, tokSim) + bonus).coerceAtMost(1.0)
    }

    private fun similarity(a: String, b: String): Double {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return 1.0 - (dp[b.length].toDouble() / maxLen.toDouble())
    }
}
