package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.potkc.overhear.PhoneSetup.Submission.Problem;

public class PhoneSetupPageTest {
    private static final String CID = "0123456789abcdef0123456789abcdef";

    @Test public void escapesEverythingThatCouldBreakOut() {
        assertEquals("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;", PhoneSetupPage.escape("<a href=\"x\" title='y'>&"));
        assertEquals("", PhoneSetupPage.escape(null));
        assertEquals("日本 ok", PhoneSetupPage.escape("日本 ok"));
    }

    @Test public void theFormIsAMobilePagePostingBackWithTheNonce() {
        String html = PhoneSetupPage.form("n0nce", false, "", "", true, null);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("name=\"viewport\""));
        assertTrue(html.contains("method=\"post\" action=\"/setup?n=n0nce\""));
        assertTrue(html.contains("name=\"key\" type=\"password\""));
        assertTrue(html.contains("name=\"client_id\""));
        assertTrue(html.contains("name=\"login\" value=\"1\" checked"));
        assertTrue(html.contains(">保存してログイン</button>"));
        assertTrue(html.contains(" required>")); // no key stored yet
        assertFalse(html.contains("class=\"err\""));
    }

    @Test public void aStoredKeyIsOnlyHinted() {
        String html = PhoneSetupPage.form("n", true, CID, "", true, null);
        assertTrue(html.contains("placeholder=\"保存済み（変更する場合のみ）\""));
        assertFalse(html.contains(" required>"));
        assertTrue(html.contains("保存済み: " + CID));
        assertTrue(html.contains("value=\"\""));
    }

    @Test public void withoutLoginTheButtonJustSaves() {
        String html = PhoneSetupPage.form("n", true, "", "", false, null);
        assertFalse(html.contains("value=\"1\" checked"));
        assertTrue(html.contains(">保存</button>"));
    }

    @Test public void echoedValuesAreEscaped() {
        String html = PhoneSetupPage.form("n\"x", true, "<s>", "\"><script>", true, "<b>&");
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<s>"));
        assertFalse(html.contains("<b>"));
        assertTrue(html.contains("value=\"&quot;&gt;&lt;script&gt;\""));
        assertTrue(html.contains("action=\"/setup?n=n&quot;x\""));
        assertTrue(html.contains("<p class=\"err\">&lt;b&gt;&amp;</p>"));
    }

    @Test public void everyProblemHasALine() {
        for (Problem p : Problem.values()) {
            if (p == Problem.NONE) assertNull(PhoneSetupPage.problem(p));
            else assertTrue(PhoneSetupPage.problem(p).length() > 0);
        }
    }

    @Test public void aMessagePageEscapesItsText() {
        String html = PhoneSetupPage.message("a<b");
        assertTrue(html.contains("<p>a&lt;b</p>"));
        assertTrue(html.contains("name=\"viewport\""));
    }
}
