package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.ClassContext;
import ru.kalinin.context.model.StructureNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContextRendererTest {

    private static final String OUTER = "forms.credit.Ценные_бумаги";
    private static final String INNER = OUTER + ".Ввод_новой";

    @Test
    void highlightsWithQualifiedNameTooltip() {
        Set<String> types = new LinkedHashSet<>(List.of(INNER, OUTER));
        List<HtmlContextRenderer.HighlightPattern> patterns =
                HtmlContextRenderer.buildHighlightPatterns(types);

        String line = "|    49|    private Ценные_бумаги.Ввод_новой ввод_новой_записи";
        String highlighted = HtmlContextRenderer.highlight(HtmlContextRenderer.escapeHtml(line), patterns);

        assertThat(highlighted).contains("title=\"" + INNER + "\"");
        assertThat(highlighted).contains("Ценные_бумаги.Ввод_новой");
    }

    @Test
    void collectAllQualifiedNamesIncludesNestedFromStructure() {
        StructureNode inner = new StructureNode(
                "class", "public static class Ввод_новой", "10-20",
                List.of(new StructureNode("field", "private String x", "12", null)));
        StructureNode outer = new StructureNode(
                "class", "public class Ценные_бумаги", "1-100",
                List.of(inner));

        ClassContext ctx = ClassContext.of(
                1, Set.of(), OUTER, 1, "main",
                List.of(outer), List.of(outer));

        Set<String> names = HtmlContextRenderer.collectAllQualifiedNames(List.of(ctx));

        assertThat(names).contains(OUTER, INNER);
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
