package com.seekstars.muselauncher;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextRulesTest {
    @Test public void preservesEmojiClusters() {
        assertEquals("A😊", TextRules.limit("A😊B", 2));
        assertEquals("👨‍👩‍👧‍👦", TextRules.limit("👨‍👩‍👧‍👦X", 1));
        assertEquals("👍🏽", TextRules.limit("👍🏽X", 1));
        assertEquals("🇨🇳", TextRules.limit("🇨🇳X", 1));
        assertEquals("e\u0301", TextRules.limit("e\u0301X", 1));
        assertEquals("", TextRules.limit("", 2));
    }

    @Test public void sessionRequiresExactNonemptyCookie() {
        assertFalse(TextRules.hasSession(null));
        assertFalse(TextRules.hasSession("other_hatch_sess=x"));
        assertFalse(TextRules.hasSession("hatch_sess= ; visitor=x"));
        assertTrue(TextRules.hasSession("visitor=x; hatch_sess=token; other=1"));
    }
}
