package org.schabi.newpipe.extractor.utils;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtilsTest {
    @Test
    void testMixedNumberWordToLong() throws ParsingException {
        assertEquals(10, Utils.mixedNumberWordToLong("10"));
        assertEquals(10.5e3, Utils.mixedNumberWordToLong("10.5K"), 0.0);
        assertEquals(10.5e6, Utils.mixedNumberWordToLong("10.5M"), 0.0);
        assertEquals(10.5e6, Utils.mixedNumberWordToLong("10,5M"), 0.0);
        assertEquals(1.5e9, Utils.mixedNumberWordToLong("1,5B"), 0.0);

        // --- Sprachfestigkeit (2026-08-02) ---
        // Ohne Einheit sind Trennzeichen TAUSENDERTRENNER. Die alte Fassung las
        // den deutschen Punkt als Dezimalpunkt: "41.054 Aufrufe" wurde zu 41,
        // "4,53 Mio. Abonnenten" zu 4. Beides live im Backend gemessen.
        assertEquals(41054, Utils.mixedNumberWordToLong("41.054 Aufrufe"));
        assertEquals(41054, Utils.mixedNumberWordToLong("41,054 views"));
        assertEquals(1799678317L, Utils.mixedNumberWordToLong("1.799.678.317 Aufrufe"));
        assertEquals(1234, Utils.mixedNumberWordToLong("1,234 views"));

        // Deutsche Wortformen der Einheit
        assertEquals(4530000, Utils.mixedNumberWordToLong("4,53 Mio. Abonnenten"));
        assertEquals(4530000, Utils.mixedNumberWordToLong("4,53 Millionen Abonnenten"));
        assertEquals(1500, Utils.mixedNumberWordToLong("1,5 Tsd. Aufrufe"));
        assertEquals(2300000000L, Utils.mixedNumberWordToLong("2,3 Mrd. Aufrufe"));

        // ⚠️ Die Falle: ein Einzelbuchstabe zaehlt NUR direkt angehaengt.
        // Sonst wuerden "42 Abonnenten" zu 42 Milliarden (das b in Abonnenten)
        // und "5 Bewertungen" ebenso.
        assertEquals(42, Utils.mixedNumberWordToLong("42 Abonnenten"));
        assertEquals(5, Utils.mixedNumberWordToLong("5 Bewertungen"));
        assertEquals(7, Utils.mixedNumberWordToLong("7 Kommentare"));

        // Geschuetztes Leerzeichen, wie YouTube es liefert
        assertEquals(4530000, Utils.mixedNumberWordToLong("4,53\u00a0Mio.\u00a0Abonnenten"));
    }

    @Test
    void testJoin() {
        assertEquals("some,random,not-null,stuff", Utils.nonEmptyAndNullJoin(",",
                "some", "null", "random", "", "not-null", null, "stuff"));
    }

    @Test
    void testGetBaseUrl() throws ParsingException {
        assertEquals("https://www.youtube.com", Utils.getBaseUrl("https://www.youtube.com/watch?v=Hu80uDzh8RY"));
        assertEquals("vnd.youtube", Utils.getBaseUrl("vnd.youtube://www.youtube.com/watch?v=jZViOEv90dI"));
        assertEquals("vnd.youtube", Utils.getBaseUrl("vnd.youtube:jZViOEv90dI"));
        assertEquals("vnd.youtube", Utils.getBaseUrl("vnd.youtube://n8X9_MgEdCg"));
        assertEquals("https://music.youtube.com", Utils.getBaseUrl("https://music.youtube.com/watch?v=O0EDx9WAelc"));
    }

    @Test
    void testFollowGoogleRedirect() {
        assertEquals("https://www.youtube.com/watch?v=Hu80uDzh8RY",
                Utils.followGoogleRedirectIfNeeded("https://www.google.it/url?sa=t&rct=j&q=&esrc=s&cd=&cad=rja&uact=8&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DHu80uDzh8RY&source=video"));
        assertEquals("https://www.youtube.com/watch?v=0b6cFWG45kA",
                Utils.followGoogleRedirectIfNeeded("https://www.google.com/url?sa=t&rct=j&q=&esrc=s&source=video&cd=&cad=rja&uact=8&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D0b6cFWG45kA"));
        assertEquals("https://soundcloud.com/ciaoproduction",
                Utils.followGoogleRedirectIfNeeded("https://www.google.com/url?sa=t&url=https%3A%2F%2Fsoundcloud.com%2Fciaoproduction&rct=j&q=&esrc=s&source=web&cd="));

        assertEquals("https://www.youtube.com/watch?v=Hu80uDzh8RY&param=xyz",
                Utils.followGoogleRedirectIfNeeded("https://www.youtube.com/watch?v=Hu80uDzh8RY&param=xyz"));
        assertEquals("https://www.youtube.com/watch?v=Hu80uDzh8RY&url=hello",
                Utils.followGoogleRedirectIfNeeded("https://www.youtube.com/watch?v=Hu80uDzh8RY&url=hello"));
    }
}
