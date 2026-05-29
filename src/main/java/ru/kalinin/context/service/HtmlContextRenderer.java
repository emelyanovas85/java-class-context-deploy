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
            body { font-family: ui-monospace, Menlo, Consolas, monospace; margin: 0; \
            background: #f6f8fa; color: #1f2328; }
            .page-header { padding: 1.5rem 1.5rem 0; }
            h1 { font-size: 1.25rem; margin: 0 0 0.5rem; }
            .meta { font-size: 0.9rem; color: #57606a; margin-bottom: 1rem; }
            .legend { margin: 0 0 0.75rem; }
            .pin-toggle { display: block; padding: 0 1.5rem 0.75rem; font-size: 0.9rem; \
            color: #57606a; cursor: pointer; user-select: none; }
            .pin-toggle input { margin-right: 0.35rem; }
            .layout-body { padding: 0 1.5rem 1.5rem; }
            .layout-body.pinned { display: flex; flex-direction: column; \
            height: calc(100vh - 11rem); min-height: 12rem; overflow: hidden; }
            .legend mark.ctx-type { background: #fff8c5; border-radius: 2px; padding: 0 2px; }
            .ctx-index { background: #fff; border: 1px solid #d0d7de; border-radius: 6px; \
            padding: 0.75rem 1rem; margin: 0 0 1rem; flex-shrink: 0; }
            .layout-body:not(.pinned) .ctx-index { max-height: 16rem; overflow-y: auto; }
            .layout-body.pinned .ctx-index { max-height: 38vh; overflow-y: auto; margin-bottom: 0.75rem; }
            .ctx-index h2 { font-size: 0.95rem; margin: 0 0 0.5rem; }
            .ctx-index ol { margin: 0; padding-left: 1.5rem; }
            .ctx-index li { margin: 0.2rem 0; }
            .ctx-index a { color: #0969da; text-decoration: none; }
            .ctx-index a:hover { text-decoration: underline; }
            .ctx-index .idx-meta { color: #57606a; font-size: 0.85em; }
            .ctx-sections { min-height: 0; }
            .layout-body.pinned .ctx-sections { flex: 1 1 auto; overflow-y: auto; \
            padding-right: 0.25rem; }
            .ctx-block { background: #fff; border: 1px solid #d0d7de; border-radius: 6px; \
            margin: 1rem 0; padding: 0.75rem 1rem; }
            .layout-body.pinned .ctx-block:first-child { margin-top: 0; }
            .ctx-block h2 { font-size: 0.95rem; margin: 0 0 0.5rem; color: #0969da; }
            .ctx-block .section-idx { color: #57606a; font-weight: normal; margin-right: 0.35rem; }
            .ctx-meta { color: #57606a; font-size: 0.85em; font-weight: normal; }
            pre.ctx { margin: 0; white-space: pre-wrap; word-break: break-word; line-height: 1.35; }
            mark.ctx-type { background: #fff8c5; border-bottom: 1px solid #d4a72c; border-radius: 2px; \
            padding: 0 1px; }
            """;

    private static final String PIN_SCRIPT = """
            <script>
            (function () {
              var cb = document.getElementById('pin-index');
              var layout = document.getElementById('layout-body');
              if (!cb || !layout) return;
              function apply() { layout.classList.toggle('pinned', cb.checked); }
              cb.addEventListener('change', apply);
              apply();
            })();
            </script>
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
        html.append("<header class=\"page-header\"><h1>Context debug</h1>");
        appendMeta(html, response);
        html.append("<p class=\"legend\">Подсветка <mark class=\"ctx-type\">жёлтым</mark> — типы, ");
        html.append("добавленные в контекст как зависимости (<code>level &gt; 0</code>, ");
        html.append(dependencyQNames.size()).append(" шт.).</p></header>");
        html.append("<label class=\"pin-toggle\"><input type=\"checkbox\" id=\"pin-index\" checked>");
        html.append("Закрепить оглавление (секции скроллятся ниже)</label>");
        html.append("<div class=\"layout-body pinned\" id=\"layout-body\">");
        appendSectionIndex(html, classes);
        html.append("<main class=\"ctx-sections\">");

        for (int i = 0; i < classes.size(); i++) {
            ClassContext ctx = classes.get(i);
            int index = i + 1;
            String sectionId = sectionId(index);
            html.append("<section class=\"ctx-block\" id=\"").append(sectionId).append("\"><h2>");
            html.append("<span class=\"section-idx\">#").append(index).append("</span>");
            appendClassLink(html, sectionId, ctx);
            html.append("</h2><pre class=\"ctx\">");
            html.append(highlight(escapeHtml(stripToStringHeader(ctx.toString())), highlightPattern));
            html.append("</pre></section>");
        }

        html.append("</main></div>");
        html.append(PIN_SCRIPT);
        html.append("</body></html>");
        return html.toString();
    }

    private static void appendSectionIndex(StringBuilder html, List<ClassContext> classes) {
        if (classes.isEmpty()) return;
        html.append("<nav class=\"ctx-index\"><h2>Оглавление (").append(classes.size()).append(")</h2><ol>");
        for (int i = 0; i < classes.size(); i++) {
            ClassContext ctx = classes.get(i);
            String sectionId = sectionId(i + 1);
            html.append("<li>");
            appendClassLink(html, sectionId, ctx);
            html.append("</li>");
        }
        html.append("</ol></nav>");
    }

    private static void appendClassLink(StringBuilder html, String sectionId, ClassContext ctx) {
        html.append("<a href=\"#").append(sectionId).append("\">");
        html.append(escapeHtml(ctx.name()));
        html.append("</a> <span class=\"ctx-meta\">");
        html.append(formatCtxMeta(ctx));
        html.append("</span>");
    }

    private static String formatCtxMeta(ClassContext ctx) {
        return "[level=" + ctx.level()
                + ", id=" + ctx.id()
                + ", callers=" + ctx.callerIds()
                + ", module=" + escapeHtml(ctx.module()) + "]";
    }

    /** Убирает строку {@code ### ...} из {@link ClassContext#toString()} — метаданные уже в заголовке. */
    static String stripToStringHeader(String text) {
        if (text == null || text.isEmpty()) return "";
        int nl = text.indexOf('\n');
        String firstLine = nl < 0 ? text : text.substring(0, nl);
        if (firstLine.startsWith("### ") && firstLine.contains("[level=")) {
            return nl < 0 ? "" : text.substring(nl + 1);
        }
        return text;
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
