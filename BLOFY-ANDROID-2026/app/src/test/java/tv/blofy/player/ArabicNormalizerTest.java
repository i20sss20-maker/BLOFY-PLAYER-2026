package tv.blofy.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ArabicNormalizerTest {
    @Test
    public void initialAlefVariantsShareOneFirstLetterKey() {
        assertEquals("ا", ArabicNormalizer.firstLetterKey("أفلام"));
        assertEquals("ا", ArabicNormalizer.firstLetterKey("إبراهيم"));
        assertEquals("ا", ArabicNormalizer.firstLetterKey("آسيا"));
        assertEquals("ا", ArabicNormalizer.firstLetterKey("ٱلبيت"));
    }

    @Test
    public void leadingWhitespaceAndArabicMarksDoNotChangeFirstLetter() {
        assertEquals("ا", ArabicNormalizer.firstLetterKey("  إِبراهيم"));
        assertEquals("م", ArabicNormalizer.firstLetterKey("مُـسلسل"));
    }

    @Test
    public void foldsCommonArabicSearchVariantsAcrossTheTitle() {
        assertEquals("مدينه عربيه", ArabicNormalizer.normalizeForSearch("مَدِينَة عَرَبِيَّة"));
        assertEquals("علي", ArabicNormalizer.normalizeForSearch("على"));
        assertEquals("سوال", ArabicNormalizer.normalizeForSearch("سؤال"));
    }

    @Test
    public void normalizesArabicAndPersianDigitsForChannelSearch() {
        assertEquals("mbc 123", ArabicNormalizer.normalizeForSearch("MBC ١۲٣"));
    }

    @Test
    public void emptyInputHasNoFirstLetter() {
        assertEquals("", ArabicNormalizer.normalizeForSearch(null));
        assertEquals("", ArabicNormalizer.firstLetterKey("   "));
    }
}
