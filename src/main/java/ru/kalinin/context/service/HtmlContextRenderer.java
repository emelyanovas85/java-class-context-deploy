package ru.kalinin.context.service;

import org.springframework.stereotype.Component;
import ru.kalinin.context.model.ClassContext;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.model.MergeRequestInfo;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * HTML-представление результата {@link ContextBuilderService#buildContext}
 * для ручной отладки: все {@link ClassContext#toString()} и подсветка типов,
 * добавленных в контекст как зависимости (level &gt; 0).
 */
@Component
public class HtmlContextRenderer {

    private static final String CSS = """
            body { font-family: ui-monospace, Menlo, Consolas, monospace; margin: 1.5rem; \
            background: #f6f8fa; color: #1f2328; }
            h1 { font-size: 1.25rem; }
            .meta { font-size: 0.9rem; color: #57606a; margin-bottom: 1rem; }
            .legend mark.ctx-type { background: #fff8c5; border-radius: 2px; padding: 0 2px; }
            .ctx-index { background: #fff; border: 1px solid #d0d7de; border-radius: 6px; \
            padding: 0.75rem 1rem; margin: 1rem 0; max-height: 16rem; overflow-y: auto; }
            .ctx-index h2 { font-size: 0.95rem; margin: 0 0 0.5rem; }
            .ctx-index ol { margin: 0; padding-left: 1.5rem; }
            .ctx-index li { margin: 0.2rem 0; }
            .ctx-index a { color: #0969da; text-decoration: none; }
            .ctx-index a:hover { text-decoration: underline; }
            .ctx-index .idx-meta { color: #57606a; font-size: 0.85em; }
            .ctx-block { background: #fff; border: 1px solid #d0d7de; border-radius: 6px; \
            margin: 1rem 0; padding: 0.75rem 1rem; }
            .ctx-block h2 { font-size: 0.95rem; margin: 0 0 0.5rem; color: #0969da; }
            .ctx-block .section-idx { color: #57606a; font-weight: normal; margin-right: 0.35rem; }
            pre.ctx { margin: 0; white-space: pre-wrap; word-break: break-word; line-height: 1.35; }
            mark.ctx-type { background: #fff8c5; border-bottom: 1px solid #d4a72c; border-radius: 2px; \
            padding: 0 1px; }
            """;

    public String render(ContextResponse response) {
        List<ClassContext> classes = response.classes();
        Set<String> dependencyQNames = classes.stream()
                .filter(c -> c.level() > 0)
                .map(ClassContext::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> patterns = buildHighlightPatterns(dependencyQNames);
        Pattern highlightPattern = compileHighlightPattern(patterns);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">");
        html.append("<title>Context debug</title><style>").append(CSS).append("</style></head><body>");
        html.append("<h1>Context debug</h1>");
        appendMeta(html, response);
        html.append("<p class=\"legend\">Подсветка <mark class=\"ctx-type\">жёлтым</mark> — типы, ");
        html.append("добавленные в контекст как зависимости (<code>level &gt; 0</code>, ");
        html.append(dependencyQNames.size()).append(" шт.).</p>");

        appendSectionIndex(html, classes);

        for (int i = 0; i < classes.size(); i++) {
            ClassContext ctx = classes.get(i);
            int index = i + 1;
            String sectionId = sectionId(index);
            html.append("<section class=\"ctx-block\" id=\"").append(sectionId).append("\"><h2>");
            html.append("<span class=\"section-idx\">#").append(index).append("</span>");
            html.append("<a href=\"#").append(sectionId).append("\">");
            html.append(escapeHtml(ctx.name()));
            html.append("</a>");
            html.append(" <span class=\"meta\">[level=").append(ctx.level());
            html.append(", id=").append(ctx.id()).append("]</span></h2>");
            html.append("<pre class=\"ctx\">");
            html.append(highlight(escapeHtml(ctx.toString()), highlightPattern));
            html.append("</pre></section>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static void appendSectionIndex(StringBuilder html, List<ClassContext> classes) {
        if (classes.isEmpty()) return;
        html.append("<nav class=\"ctx-index\"><h2>Оглавление (").append(classes.size()).append(")</h2><ol>");
        for (int i = 0; i < classes.size(); i++) {
            ClassContext ctx = classes.get(i);
            int index = i + 1;
            String sectionId = sectionId(index);
            html.append("<li><a href=\"#").append(sectionId).append("\">");
            html.append(index).append(". ").append(escapeHtml(ctx.name()));
            html.append("</a> <span class=\"idx-meta\">[level=").append(ctx.level());
            html.append(", id=").append(ctx.id()).append("]</span></li>");
        }
        html.append("</ol></nav>");
    }

    private static String sectionId(int index) {
        return "ctx-" + index;
    }

    private static void appendMeta(StringBuilder html, ContextResponse response) {
        MergeRequestInfo mr = response.mergeRequest();
        html.append("<p class=\"meta\">");
        if (mr != null) {
            html.append("MR !").append(mr.iid()).append(" — ").append(escapeHtml(mr.title()));
            html.append("<br>source: <code>").append(escapeHtml(mr.sourceBranch())).append("</code>");
            html.append(" → target: <code>").append(escapeHtml(mr.targetBranch())).append("</code>");
            html.append("<br>");
        }
        html.append("depth=").append(response.requestedDepth());
        html.append(", classes=").append(response.totalClassesAnalyzed());
        html.append("</p>");
    }

    /**
     * Паттерны для подсветки: полное имя, суффикс {@code Outer.Inner}, уникальное simple-имя.
     */
    static List<String> buildHighlightPatterns(Set<String> qualifiedNames) {
        if (qualifiedNames.isEmpty()) return List.of();

        Map<String, Integer> simpleCounts = new HashMap<>();
        for (String qn : qualifiedNames) {
            simpleCounts.merge(simpleName(qn), 1, Integer::sum);
        }

        Set<String> patterns = new LinkedHashSet<>();
        for (String qn : qualifiedNames) {
            patterns.add(qn);
            String two = twoSegmentSuffix(qn);
            if (two != null) {
                patterns.add(two);
            }
            String simple = simpleName(qn);
            if (simpleCounts.get(simple) == 1) {
                patterns.add(simple);
            }
        }

        return patterns.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    static Pattern compileHighlightPattern(List<String> patterns) {
        if (patterns.isEmpty()) {
            return Pattern.compile("(?!)");
        }
        String alternation = patterns.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(alternation);
    }

    static String highlight(String escapedText, Pattern pattern) {
        Matcher m = pattern.matcher(escapedText);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(escapedText, last, m.start());
            out.append("<mark class=\"ctx-type\" title=\"in context\">");
            out.append(m.group());
            out.append("</mark>");
            last = m.end();
        }
        out.append(escapedText.substring(last));
        return out.toString();
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private static String twoSegmentSuffix(String qualifiedName) {
        int last = qualifiedName.lastIndexOf('.');
        if (last <= 0) return null;
        int prev = qualifiedName.lastIndexOf('.', last - 1);
        if (prev < 0) return null;
        return qualifiedName.substring(prev + 1);
    }

    static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
