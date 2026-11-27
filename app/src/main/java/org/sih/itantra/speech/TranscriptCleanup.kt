package org.sih.itantra.speech

/**
 * Repairs invalid letter sequences the CTC recogniser sometimes produces when two sub-word pieces
 * are glued together, in every Indian script (each script has the same layout inside its block):
 *
 *  - a virama (dead-consonant mark) directly followed by a vowel sign:  "த்தில்ிருந்து" -> "த்திலிருந்து"
 *  - the same vowel sign twice in a row:                                 "த்துுறை" -> "த்துறை"
 *
 * Such sequences are never valid spelling, and they also trip up the TTS voice and translation.
 * Mirrored in tools/eval_wer_fleurs.py so the benchmark scores exactly what the app produces.
 */
object TranscriptCleanup {

    private val BLOCKS = listOf(0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)

    private fun blockOf(c: Char): Int? = BLOCKS.firstOrNull { c.code - it in 0..0x7F }

    private fun isVirama(c: Char) = blockOf(c)?.let { c.code - it == 0x4D } == true

    /** Dependent vowel signs: offsets 0x3E..0x4C (aa .. au), 0x55..0x57 (length marks), 0x62..0x63. */
    private fun isVowelSign(c: Char): Boolean {
        val off = c.code - (blockOf(c) ?: return false)
        return off in 0x3E..0x4C || off in 0x55..0x57 || off == 0x62 || off == 0x63
    }

    fun clean(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (isVirama(c) && next != null && isVowelSign(next) && blockOf(next) == blockOf(c)) continue
            if (isVowelSign(c) && out.isNotEmpty() && out.last() == c) continue
            out.append(c)
        }
        return out.toString()
    }
}
