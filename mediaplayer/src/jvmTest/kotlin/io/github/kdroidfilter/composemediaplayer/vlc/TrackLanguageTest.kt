package io.github.kdroidfilter.composemediaplayer.vlc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [trackLanguageOf] is what turns libVLC's own track descriptions into something a picker can show,
 * and its input is a native string this project cannot change — hence pinning the three shapes it
 * comes in, without a running libVLC.
 */
class TrackLanguageTest {

    @Test
    fun bracketedLanguageWins() {
        assertEquals("English", trackLanguageOf("Track 1 - [English]"))
    }

    @Test
    fun aPlainDescriptionIsTheLanguage() {
        assertEquals("French", trackLanguageOf("  French  "))
    }

    @Test
    fun noDescriptionIsNoLanguage() {
        assertEquals("", trackLanguageOf(null))
        assertEquals("", trackLanguageOf("   "))
    }

    /** An unbalanced bracket is not a language marker — take the description as it stands. */
    @Test
    fun unbalancedBracketsFallBackToTheWholeString() {
        assertEquals("Track 1 - ]English[", trackLanguageOf("Track 1 - ]English["))
    }
}
