package org.sih.itantra

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sih.itantra.speech.TranscriptCleanup

class TranscriptCleanupTest {
    @Test
    fun repairsGluedSubwordPieces() {
        assertEquals("நிலையத்திலிருந்து", TranscriptCleanup.clean("நிலையத்தில்ிருந்து"))   // virama + vowel sign
        assertEquals("சுங்கத்துறை", TranscriptCleanup.clean("சுங்கத்துுறை"))                // doubled vowel sign
        assertEquals("किताबें", TranscriptCleanup.clean("किताब्ें"))                         // Devanagari too
    }

    @Test
    fun leavesValidTextAlone() {
        for (t in listOf("எங்களுக்கு உணவும் தண்ணீரும் தேவை", "पुल के पास चिकित्सा दल की जरूरत है",
                         "ನಮಗೆ ಊಟ ಮತ್ತು ನೀರು ಬೇಕು", "We need food and water", "")) {
            assertEquals(t, TranscriptCleanup.clean(t))
        }
    }
}
