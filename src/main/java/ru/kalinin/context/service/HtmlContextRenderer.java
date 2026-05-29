package ru.kalinin.context.service;

import org.springframework.stereotype.Component;
import ru.kalinin.context.model.ClassContext;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.model.MergeRequestInfo;
import ru.kalinin.context.model.ModifiedClassContext;
import ru.kalinin.context.model.StructureNode;
import ru.kalinin.context.model.UnchangedClassContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTML-представление результата {@link ContextBuilderService#buildContext}
 * для ручной отладки: все {@link ClassContext#toString()} и подсветка типов,
 * присутствующих в контексте (включая nested внутри структур outer-классов).
 */
@Component
public class HtmlContextRenderer {

    /** Текстовый фрагмент в выводе → fully-qualified name для {@code title}. */
    record HighlightPattern(String text, String qualifiedName) {}

    private static final String CSS = """
            body { font-family: ui-monospace, Menlo, Consolas, monospace; margin: 0; \
            background: #f6f8fa; color: #1f2328; }
            .page-header { padding: 1.5rem 1.5rem 0; }
            h1 { font-size: 1.25rem; margin: 0 0 0.5rem; }
            .meta { font-size: 0.9rem; color: #57606a; margin-bottom: 1rem; }
            .legend { margin: 0 0 0.75rem; }
            .page-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem 1.5rem; \
            padding: 0 1.5rem 0.75rem; font-size: 0.9rem; color: #57606a; }
            .page-toolbar label { cursor: pointer; user-select: none; display: inline-flex; \
            align-items: center; gap: 0.35rem; }
            .layout-body { padding: 0 1.5rem 1.5rem; }
            .layout-body.pinned { display: flex; flex-direction: column; \
            height: calc(100vh - 11rem); min-height: 12rem; overflow: hidden; }
            .legend mark.ctx-type { background: #fff8c5; border-radius: 2px; padding: 0 2px; }
            .ctx-index { background: #fff; border: 1px solid #d0d7de; border-radius: 6px; \
            padding: 0.75rem 1rem; margin: 0 0 1rem; flex-shrink: 0; overflow: auto; \
            resize: vertical; min-height: 5rem; max-height: 75vh; }
            .layout-body.pinned .ctx-index { height: 38vh; margin-bottom: 0.75rem; }
            .layout-body:not(.pinned) .ctx-index { max-height: 38vh; }
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
            padding: 0 1px; cursor: help; }
            """;

    private static final String LAYOUT_SCRIPT = """
            <script>
            (function () {
              var cb = document.getElementById('pin-index');
              var layout = document.getElementById('layout-body');
              var KEY_PIN = 'ctx-index-pinned';
              if (!cb || !layout) return;
              var savedPin = localStorage.getItem(KEY_PIN);
              if (savedPin !== null) cb.checked = savedPin === '1';
              function applyPin() {
                layout.classList.toggle('pinned', cb.checked);
                localStorage.setItem(KEY_PIN, cb.checked ? '1' : '0');
              }
              cb.addEventListener('change', applyPin);
              applyPin();
            })();
            </script>
            """;

    public String render(ContextResponse response) {
        List<ClassContext> classes = response.classes();
        Set<String> allQualifiedNames = collectAllQualifiedNames(classes);
        List<HighlightPattern> highlightPatterns = buildHighlightPatterns(allQualifiedNames);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">");
        html.append("<title>Context debug</title><style>").append(CSS).append("</style></head><body>");
        html.append("<header class=\"page-header\"><h1>Context debug</h1>");
        appendMeta(html, response);
        html.append("<p class=\"legend\">Подсветка <mark class=\"ctx-type\">жёлтым</mark> — все типы ");
        html.append("из контекста (").append(allQualifiedNames.size());
        html.append(" шт., включая nested). Наведите — qualified name.</p></header>");
        html.append("<div class=\"page-toolbar\"><label><input type=\"checkbox\" id=\"pin-index\" checked>");
        html.append("Закрепить оглавление</label></div>");
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
            html.append(highlight(escapeHtml(stripToStringHeader(ctx.toString())), highlightPatterns));
            html.append("</pre></section>");
        }

        html.append("</main></div>");
        html.append(LAYOUT_SCRIPT);
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
     * Все qualified name: контексты ответа + nested-типы из деревьев {@link StructureNode}.
     */
    static Set<String> collectAllQualifiedNames(List<ClassContext> classes) {
        Set<String> qnames = new LinkedHashSet<>();
        for (ClassContext ctx : classes) {
            qnames.add(ctx.name());
            for (List<StructureNode> roots : structureRoots(ctx)) {
                collectNestedFromStructure(roots, ctx.name(), qnames);
            }
        }
        return qnames;
    }

    private static List<List<StructureNode>> structureRoots(ClassContext ctx) {
        if (ctx instanceof UnchangedClassContext u) {
            return u.structure() != null ? List.of(u.structure()) : List.of();
        }
        ModifiedClassContext m = (ModifiedClassContext) ctx;
        List<List<StructureNode>> roots = new ArrayList<>(2);
        if (m.structureSource() != null) roots.add(m.structureSource());
        if (m.structureTarget() != null) roots.add(m.structureTarget());
        return roots;
    }

    private static void collectNestedFromStructure(
            List<StructureNode> nodes,
            String enclosingQn,
            Set<String> qnames) {
        if (nodes == null) return;
        for (StructureNode node : nodes) {
            if (isTypeNode(node)) {
                String simple = simpleNameFromSignature(node.signature(), node.type());
                boolean isOuterShell = simple != null && simple.equals(simpleName(enclosingQn));
                if (simple != null && !simple.isEmpty() && !isOuterShell) {
                    String nestedQn = enclosingQn + '.' + simple;
                    qnames.add(nestedQn);
                    if (node.children() != null) {
                        collectNestedFromStructure(node.children(), nestedQn, qnames);
                    }
                } else if (node.children() != null) {
                    collectNestedFromStructure(node.children(), enclosingQn, qnames);
                }
            } else if (node.children() != null) {
                collectNestedFromStructure(node.children(), enclosingQn, qnames);
            }
        }
    }

    private static boolean isTypeNode(StructureNode node) {
        return switch (node.type()) {
            case "class", "interface", "enum", "record", "annotation" -> true;
            default -> false;
        };
    }

    private static String simpleNameFromSignature(String signature, String kind) {
        if (signature == null || signature.isBlank()) return null;
        String keyword = switch (kind) {
            case "class", "interface" -> kind;
            case "record" -> "record";
            case "enum" -> "enum";
            case "annotation" -> "@interface";
            default -> null;
        };
        if (keyword != null) {
            int idx = signature.indexOf(keyword);
            if (idx >= 0) {
                String rest = signature.substring(idx + keyword.length()).trim();
                StringBuilder name = new StringBuilder();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (Character.isJavaIdentifierPart(c)) {
                        name.append(c);
                    } else {
                        break;
                    }
                }
                if (!name.isEmpty()) return name.toString();
            }
        }
        String[] parts = signature.trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    /**
     * Паттерны для подсветки: полное имя, суффикс {@code Outer.Inner}, уникальное simple-имя.
     */
    static List<HighlightPattern> buildHighlightPatterns(Set<String> qualifiedNames) {
        if (qualifiedNames.isEmpty()) return List.of();

        Map<String, Integer> simpleCounts = new HashMap<>();
        for (String qn : qualifiedNames) {
            simpleCounts.merge(simpleName(qn), 1, Integer::sum);
        }

        Map<String, String> textToQn = new LinkedHashMap<>();
        for (String qn : qualifiedNames) {
            putPattern(textToQn, qn, qn);
            String two = twoSegmentSuffix(qn);
            if (two != null) {
                putPattern(textToQn, two, qn);
            }
            String simple = simpleName(qn);
            if (simpleCounts.get(simple) == 1) {
                putPattern(textToQn, simple, qn);
            }
        }

        return textToQn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .map(e -> new HighlightPattern(e.getKey(), e.getValue()))
                .toList();
    }

    private static void putPattern(Map<String, String> textToQn, String text, String qualifiedName) {
        textToQn.putIfAbsent(text, qualifiedName);
    }

    /**
     * Тип в сигнатуре — не подстрока идентификатора.
     * Слева: {@code @ , < ( [} или пробел; справа: {@code . > , ) ] ; [ :} или пробел.
     */
    static boolean isTypeTokenAt(String text, int start, int end) {
        boolean leftOk = start == 0 || isLeftTypeBoundary(text.charAt(start - 1));
        boolean rightOk = end >= text.length() || isRightTypeBoundary(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isLeftTypeBoundary(char c) {
        return c == '@' || c == ',' || c == '<' || c == '(' || c == '['
                || Character.isWhitespace(c);
    }

    private static boolean isRightTypeBoundary(char c) {
        return c == '.' || c == '>' || c == ',' || c == ')' || c == ']' || c == ';'
                || c == '[' || c == ':' || Character.isWhitespace(c);
    }

    static String highlight(String escapedText, List<HighlightPattern> patterns) {
        if (patterns.isEmpty() || escapedText.isEmpty()) return escapedText;

        StringBuilder out = new StringBuilder(escapedText.length());
        int pos = 0;
        while (pos < escapedText.length()) {
            HighlightPattern best = null;
            for (HighlightPattern p : patterns) {
                String text = p.text();
                int end = pos + text.length();
                if (escapedText.startsWith(text, pos)
                        && isTypeTokenAt(escapedText, pos, end)
                        && (best == null || text.length() > best.text().length())) {
                    best = p;
                }
            }
            if (best == null) {
                out.append(escapedText.charAt(pos));
                pos++;
            } else {
                out.append("<mark class=\"ctx-type\" title=\"");
                out.append(escapeHtml(best.qualifiedName()));
                out.append("\">");
                out.append(best.text());
                out.append("</mark>");
                pos += best.text().length();
            }
        }
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
