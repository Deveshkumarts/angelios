package org.sih.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.sih.itantra.domain.model.Language
import org.sih.itantra.speech.TtsTextNormalizer

class TtsTextNormalizerTest {

    @Test
    fun englishNumbersBecomeWords() {
        assertEquals(
            "three people injured, twenty five km away, battery eighty percent",
            TtsTextNormalizer.normalize("3 people injured, 25 km away, battery 80%", Language.ENGLISH)
        )
        assertEquals("one thousand two hundred five", TtsTextNormalizer.normalize("1,205", Language.ENGLISH))
        assertEquals("gps twelve point nine seven",
            TtsTextNormalizer.normalize("gps 12.97", Language.ENGLISH))
    }

    @Test
    fun indicNumbersUseRealWordsUpTo99() {
        assertEquals("तीन लोग, पच्चीस किलोमीटर", TtsTextNormalizer.normalize("3 लोग, 25 किलोमीटर", Language.HINDI))
        assertEquals("बारह दशमलव नौ सात", TtsTextNormalizer.normalize("12.97", Language.HINDI))
        assertEquals("மூன்று பேர்", TtsTextNormalizer.normalize("3 பேர்", Language.TAMIL))
        assertEquals("ಇಪ್ಪತ್ತೈದು", TtsTextNormalizer.normalize("25", Language.KANNADA))
        // bigger numbers and leading zeros: radio-style digits
        assertEquals("चार पाँच शून्य", TtsTextNormalizer.normalize("450", Language.HINDI))
        assertEquals("शून्य पाँच", TtsTextNormalizer.normalize("05", Language.HINDI))
    }

    @Test
    fun nativeScriptDigitsAndSymbolsAreSpelledOut() {
        assertEquals("तीन", TtsTextNormalizer.normalize("३", Language.HINDI))          // Devanagari digit
        assertEquals("আশি শতাংশ", TtsTextNormalizer.normalize("৮০%", Language.BENGALI))
        assertEquals("food and water", TtsTextNormalizer.normalize("food & water", Language.ENGLISH))
        for (lang in Language.entries) {
            assertFalse(lang.name, TtsTextNormalizer.normalize("4 5.6 7%", lang).any { it.isDigit() && it.code < 128 })
        }
    }
}
