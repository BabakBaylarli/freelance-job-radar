package com.babakbaylarli.jobradar.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTextTest {

    @Test
    void convertsParagraphsListsAndEntities() {
        String text = HtmlText.toPlain("<p>We build <b>payments</b> &amp; more.</p><ul><li>Java</li><li>Kafka</li></ul><p>Remote!</p>");

        assertThat(text).isEqualTo("We build payments & more.\n\n- Java\n- Kafka\n\nRemote!");
    }

    @Test
    void handlesLineBreaksAndNull() {
        assertThat(HtmlText.toPlain("a<br>b")).isEqualTo("a\nb");
        assertThat(HtmlText.toPlain(null)).isEmpty();
        assertThat(HtmlText.toPlain("   ")).isEmpty();
    }

    @Test
    void dropsScriptsAsTextButKeepsLinkText() {
        assertThat(HtmlText.toPlain("Apply <a href=\"https://x.test\">here</a>")).isEqualTo("Apply here");
    }

    @Test
    void truncatesOnWordBoundary() {
        assertThat(HtmlText.truncate("hello wonderful world today", 18)).isEqualTo("hello wonderful…");
        assertThat(HtmlText.truncate("hello wonderful world", 14)).isEqualTo("hello wonderf…"); // no good break: hard cut
        assertThat(HtmlText.truncate("short", 14)).isEqualTo("short");
        assertThat(HtmlText.firstLine("line one\nline two", 50)).isEqualTo("line one");
    }
}
