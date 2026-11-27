package org.sih.itantra.speech

import org.sih.itantra.domain.model.Language

/**
 * Rewrites text so the MMS voices can say it. MMS models are trained on letters only: digits,
 * "%" or "&" are skipped or garbled ("GPS 12.97" came out as "GPS"). This spells them out in the
 * voice's own language before synthesis.
 *
 * English gets full number words ("25" -> "twenty five"). Indian languages say 0-99 as real words
 * ("25" -> "पच्चीस", see NumberWords) and read bigger numbers and decimals digit by digit, radio style
 * ("450" -> "चार पाँच शून्य", "12.97" -> "बारह दशमलव नौ सात").
 */
object TtsTextNormalizer {

    private val DIGITS: Map<Language, List<String>> = mapOf(
        Language.HINDI to listOf("शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ"),
        Language.MARATHI to listOf("शून्य", "एक", "दोन", "तीन", "चार", "पाच", "सहा", "सात", "आठ", "नऊ"),
        Language.BENGALI to listOf("শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়"),
        Language.GUJARATI to listOf("શૂન્ય", "એક", "બે", "ત્રણ", "ચાર", "પાંચ", "છ", "સાત", "આઠ", "નવ"),
        Language.PUNJABI to listOf("ਸਿਫ਼ਰ", "ਇੱਕ", "ਦੋ", "ਤਿੰਨ", "ਚਾਰ", "ਪੰਜ", "ਛੇ", "ਸੱਤ", "ਅੱਠ", "ਨੌਂ"),
        Language.ODIA to listOf("ଶୂନ", "ଏକ", "ଦୁଇ", "ତିନି", "ଚାରି", "ପାଞ୍ଚ", "ଛଅ", "ସାତ", "ଆଠ", "ନଅ"),
        Language.TAMIL to listOf("சுழியம்", "ஒன்று", "இரண்டு", "மூன்று", "நான்கு", "ஐந்து", "ஆறு", "ஏழு", "எட்டு", "ஒன்பது"),
        Language.TELUGU to listOf("సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది"),
        Language.KANNADA to listOf("ಸೊನ್ನೆ", "ಒಂದು", "ಎರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ಐದು", "ಆರು", "ಏಳು", "ಎಂಟು", "ಒಂಬತ್ತು"),
        Language.MALAYALAM to listOf("പൂജ്യം", "ഒന്ന്", "രണ്ട്", "മൂന്ന്", "നാല്", "അഞ്ച്", "ആറ്", "ഏഴ്", "എട്ട്", "ഒമ്പത്")
    )

    private val POINT = mapOf(
        Language.HINDI to "दशमलव", Language.MARATHI to "दशांश", Language.BENGALI to "দশমিক",
        Language.GUJARATI to "દશાંશ", Language.PUNJABI to "ਦਸ਼ਮਲਵ", Language.ODIA to "ଦଶମିକ",
        Language.TAMIL to "புள்ளி", Language.TELUGU to "పాయింట్", Language.KANNADA to "ಪಾಯಿಂಟ್",
        Language.MALAYALAM to "പോയിന്റ്", Language.ENGLISH to "point"
    )

    private val PERCENT = mapOf(
        Language.HINDI to "प्रतिशत", Language.MARATHI to "टक्के", Language.BENGALI to "শতাংশ",
        Language.GUJARATI to "ટકા", Language.PUNJABI to "ਪ੍ਰਤੀਸ਼ਤ", Language.ODIA to "ପ୍ରତିଶତ",
        Language.TAMIL to "சதவீதம்", Language.TELUGU to "శాతం", Language.KANNADA to "ಶೇಕಡಾ",
        Language.MALAYALAM to "ശതമാനം", Language.ENGLISH to "percent"
    )

    private val AND = mapOf(
        Language.HINDI to "और", Language.MARATHI to "आणि", Language.BENGALI to "এবং",
        Language.GUJARATI to "અને", Language.PUNJABI to "ਅਤੇ", Language.ODIA to "ଏବଂ",
        Language.TAMIL to "மற்றும்", Language.TELUGU to "మరియు", Language.KANNADA to "ಮತ್ತು",
        Language.MALAYALAM to "ഒപ്പം", Language.ENGLISH to "and"
    )

    private val EN_ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val EN_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** Unicode blocks whose 0x66..0x6F code points are digits (Devanagari, Bengali, ... Malayalam). */
    private val DIGIT_BLOCKS = listOf(0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)

    private val NUMBER = Regex("""(-?)(\d+(?:,\d{2,3})*)(?:\.(\d+))?(\s*%)?""")

    fun normalize(text: String, voice: Language): String {
        // NFC: decomposed vowel signs (Telugu U+0C46 U+0C56, ...) aren't in the voices' vocabularies
        var t = asciiDigits(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC))
        t = t.replace("&", " ${AND.getValue(voice)} ").replace("+", " ")
        t = NUMBER.replace(t) { m -> " " + speakNumber(m, voice) + " " }
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun speakNumber(m: MatchResult, voice: Language): String {
        val (minus, intPart, frac, pct) = m.destructured
        val digitsOnly = intPart.replace(",", "")
        val words = StringBuilder()
        if (minus.isNotEmpty()) words.append(if (voice == Language.ENGLISH) "minus " else "- ")
        val natural = NumberWords.UNDER_100[voice]
        words.append(
            when {
                voice == Language.ENGLISH && digitsOnly.length <= 9 -> englishCardinal(digitsOnly.toLong())
                // 0-99 as real words ("पच्चीस"); a leading zero ("05") is read digit by digit
                natural != null && digitsOnly.length <= 2 && !(digitsOnly.length == 2 && digitsOnly[0] == '0') ->
                    natural[digitsOnly.toInt()]
                else -> spellDigits(digitsOnly, voice)
            }
        )
        if (frac.isNotEmpty()) words.append(' ').append(POINT.getValue(voice)).append(' ').append(spellDigits(frac, voice))
        if (pct.isNotBlank()) words.append(' ').append(PERCENT.getValue(voice))
        return words.toString()
    }

    private fun spellDigits(digits: String, voice: Language): String {
        val names = DIGITS[voice] ?: EN_ONES.take(10)
        return digits.map { names[it - '0'] }.joinToString(" ")
    }

    private fun englishCardinal(n: Long): String {
        if (n < 20) return EN_ONES[n.toInt()]
        if (n < 100) return EN_TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + EN_ONES[(n % 10).toInt()] else ""
        if (n < 1000) return EN_ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + englishCardinal(n % 100) else ""
        for ((size, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")) {
            if (n >= size) return englishCardinal(n / size) + " $name" + if (n % size != 0L) " " + englishCardinal(n % size) else ""
        }
        return n.toString()
    }

    private fun asciiDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val block = DIGIT_BLOCKS.firstOrNull { c.code - it - 0x66 in 0..9 }
            sb.append(if (block != null) '0' + (c.code - block - 0x66) else c)
        }
        return sb.toString()
    }
}
