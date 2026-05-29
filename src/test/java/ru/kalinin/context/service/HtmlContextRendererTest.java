package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContextRendererTest {

    private static final String OUTER = "forms.credit.Ценные_бумаги";
    private static final String INNER = OUTER + ".Ввод_новой";

    @Test
    void highlightsQualifiedAndNestedSuffixInText() {
        Set<String> deps = new LinkedHashSet<>(List.of(INNER, OUTER));
        Pattern pattern = HtmlContextRenderer.compileHighlightPattern(
                HtmlContextRenderer.buildHighlightPatterns(deps));

        String line = "|    49|    private Ценные_бумаги.Ввод_новой ввод_новой_записи";
        String highlighted = HtmlContextRenderer.highlight(HtmlContextRenderer.escapeHtml(line), pattern);

        assertThat(highlighted).contains("<mark class=\"ctx-type\"");
        assertThat(highlighted).contains("Ценные_бумаги.Ввод_новой");
        assertThat(highlighted).doesNotContain("&lt;mark");
    }

    @Test
    void escapeHtmlEscapesSpecialChars() {
        assertThat(HtmlContextRenderer.escapeHtml("a < b & c"))
                .isEqualTo("a &lt; b &amp; c");
    }

    @Test
    void stripToStringHeaderRemovesDuplicateMetaLine() {
        String raw = "### com.foo.Bar  [level=1, id=2, callers=[3], module=main]  [unchanged]\n|  1|void m()";
        assertThat(HtmlContextRenderer.stripToStringHeader(raw)).isEqualTo("|  1|void m()");
    }
}
