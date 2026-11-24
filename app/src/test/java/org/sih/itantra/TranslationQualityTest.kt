package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.Language
import org.sih.itantra.ml.translation.OfflineTranslatorEngine

/** Regression tests for the field-reported bad translations (mixed script, dropped words). */
class TranslationQualityTest {
    private val engine = OfflineTranslatorEngine()

    private fun tr(text: String, to: Language) = engine.translate(text, to, Language.ENGLISH)

    private fun assertNoStrayEnglish(result: String) {
        assertFalse("mixed-script output: '$result'", Regex("[A-Za-z]{2,}").containsMatchIn(result))
    }

    @Test fun oneGuyInjuredHereIsNotTreatedAsAName() {
        for (input in listOf("one guys injured here", "one guy injured here", "someone is injured here")) {
            val out = tr(input, Language.TAMIL)
            assertNoStrayEnglish(out)
            assertTrue("'$input' -> '$out' lost 'injured'", out.contains("காயம"))
            assertTrue("'$input' -> '$out' lost 'here'", out.contains("இங்க"))
        }
    }

    @Test fun pleaseComeAndHelpUsKeepsTheVerb() {
        val out = tr("please come and help us", Language.TAMIL)
        assertEquals("தயவுசெய்து வந்து எங்களுக்கு உதவுங்கள்", out)
        assertTrue(tr("please come and help us", Language.HINDI).contains("आइए"))
    }

    @Test fun mePatternDoesNotFireInsideOtherWords() {
        // "someone"/"come" contain "me"; that must not turn the subject into "I".
        val out = tr("someone injured", Language.TAMIL)
        assertFalse("subject wrongly resolved to me: '$out'", out.contains("நான்") || out.contains("எனக்கு"))
    }

    @Test fun trulyFirstPersonStillWorks() {
        assertEquals("நான் காயமடைந்துள்ளேன்", tr("I am injured", Language.TAMIL))
    }

    private fun from(src: Language, text: String, to: Language) = engine.translate(text, to, src)

    private fun assertClean(result: String, target: Language) {
        val isEn = target == Language.ENGLISH
        assertFalse("mixed script: '$result'", Regex("[A-Za-z]{2,}").containsMatchIn(result) && !isEn)
        if (isEn) assertFalse("untranslated Indic text: '$result'", result.any { it.code > 0x0900 && it.code < 0x0E00 })
    }

    @Test fun everyIndicSourceTranslatesToEnglish() {
        val cases = mapOf(
            Language.TAMIL to "தயவுசெய்து வந்து எங்களுக்கு உதவுங்கள்",
            Language.HINDI to "कृपया आकर हमारी मदद करें",
            Language.TELUGU to "దయచేసి వచ్చి మాకు సహాయం చేయండి",
            Language.KANNADA to "ದಯವಿಟ್ಟು ಬಂದು ನಮಗೆ ಸಹಾಯ ಮಾಡಿ",
            Language.MALAYALAM to "ദയവായി വന്ന് ഞങ്ങളെ സഹായിക്കൂ",
            Language.BENGALI to "দয়া করে এসে আমাদের সাহায্য করুন"
        )
        for ((lang, text) in cases) {
            val out = from(lang, text, Language.ENGLISH)
            assertEquals("${lang.code} -> en", "Please come and help us.", out)
        }
    }

    @Test fun indicToIndicNeverMixesScripts() {
        val src = "మాకు నీళ్ళు కావాలి" // Telugu: "we need water"
        for (target in listOf(Language.TAMIL, Language.HINDI, Language.KANNADA, Language.MALAYALAM, Language.BENGALI)) {
            assertClean(from(Language.TELUGU, src, target), target)
        }
        assertEquals("எங்களுக்கு தண்ணீர் வேணும்", from(Language.TELUGU, src, Language.TAMIL))
        assertEquals("हमें पानी चाहिए", from(Language.TELUGU, src, Language.HINDI))
    }

    @Test fun weAreTrappedUsesFirstPersonVerb() {
        val out = from(Language.TAMIL, "நாங்கள் சிக்கிக்கொண்டோம்", Language.TELUGU)
        assertTrue("got '$out'", out.endsWith("చిక్కుకున్నాం"))
    }

    @Test fun unknownWordsFallBackToOriginalNotGarbage() {
        // "Ramesh" is not in the lexicon: the result must be the original or a clean translation, never a mash-up.
        val out = from(Language.HINDI, "राजू के पैर में चोट लगी है", Language.ENGLISH)
        assertFalse("mash-up: '$out'", out.contains("legs में"))
    }

    @Test fun extraContentIsNotSilentlyDropped() {
        // The trauma template cannot express "on the road"; it must decline rather than drop words.
        val out = tr("one guy injured on the highway near the bridge", Language.TAMIL)
        assertNotEquals("ஒருவர் காயமடைந்துள்ளார்", out)
    }
}
