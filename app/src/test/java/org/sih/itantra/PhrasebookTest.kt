package org.sih.itantra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards the curated emergency phrasebook: aligned lists, no blanks, correct script per language. */
class PhrasebookTest {
    private val book = JSONObject(File("src/main/assets/phrasebook.json").readText(Charsets.UTF_8))

    private val scripts = mapOf(
        "hin_Deva" to 0x0900..0x097F, "mar_Deva" to 0x0900..0x097F,
        "ben_Beng" to 0x0980..0x09FF, "pan_Guru" to 0x0A00..0x0A7F,
        "guj_Gujr" to 0x0A80..0x0AFF, "ory_Orya" to 0x0B00..0x0B7F,
        "tam_Taml" to 0x0B80..0x0BFF, "tel_Telu" to 0x0C00..0x0C7F,
        "kan_Knda" to 0x0C80..0x0CFF, "mal_Mlym" to 0x0D00..0x0D7F
    )

    @Test fun everyLanguageIsAlignedWithEnglish() {
        val en = book.getJSONArray("en")
        for (lang in scripts.keys) {
            val arr = book.getJSONArray(lang)
            assertEquals("$lang length", en.length(), arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i)
                assertTrue("$lang[$i] blank", s.isNotBlank())
                assertTrue("$lang duplicate '$s'", seen.add(s))
            }
        }
    }

    @Test fun phrasesAreInTheRightScript() {
        for ((lang, range) in scripts) {
            val arr = book.getJSONArray(lang)
            for (i in 0 until arr.length()) {
                val s = arr.getString(i)
                val letters = s.filter { it.isLetter() }
                val inScript = letters.count { it.code in range }
                assertEquals("$lang[$i] '$s' has foreign-script letters", letters.length, inScript)
            }
        }
    }
}
