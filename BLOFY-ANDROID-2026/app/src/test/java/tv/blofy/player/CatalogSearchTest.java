package tv.blofy.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CatalogSearchTest {
    @Test public void buildsArabicPrefixTermsWithoutLeadingWildcard() {
        assertEquals("مدينه* AND عربيه*", CatalogSearch.prefixQuery("مَدِينَة عربية"));
    }

    @Test public void dropsPunctuationAndKeepsUsefulTokens() {
        assertEquals("mbc* AND 1*", CatalogSearch.prefixQuery("MBC - (1)"));
    }

    @Test public void punctuationOnlyDoesNotRunAQuery() {
        assertEquals("", CatalogSearch.prefixQuery("*** --"));
    }
}
