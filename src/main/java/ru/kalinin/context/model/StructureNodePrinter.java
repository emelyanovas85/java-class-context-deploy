package ru.kalinin.context.model;

import java.util.Comparator;
import java.util.List;

/**
 * Рендерит дерево {@link StructureNode} в псевдо-Markdown.
 *
 * <p>Формат строки:
 * <pre>
 * |{rows,10}|{indent}{signatureLine}
 * </pre>
 * где:
 * <ul>
 *   <li>{@code rows}   — диапазон строк ({@code "17"} или {@code "19-22"}),
 *       выравнивается по правому краю в поле шириной 10 символов.</li>
 *   <li>{@code indent} — 4 пробела × глубина узла.</li>
 *   <li>Многострочная сигнатура разбивается на части: каждая строка
 *       получает одинаковый отступ; колонка rows заполняется только
 *       для первой строки (у остальных — пробелы).</li>
 *   <li>Узлы одного уровня сортируются по начальному номеру строки перед выводом.
 *       Узлы без номера строки опускаются в конец.</li>
 * </ul>
 */
public final class StructureNodePrinter {

    /** Ширина колонки с номерами строк (между двумя '|'). */
    private static final int ROWS_WIDTH = 10;
    /** Размер одного уровня отступа. */
    private static final int INDENT_SIZE = 4;

    private StructureNodePrinter() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Рендерит список узлов (корневой уровень одного файла).
     *
     * @param nodes  список узлов верхнего уровня
     * @param depth  начальная глубина (обычно 0)
     * @return       многострочная строка
     */
    public static String render(List<StructureNode> nodes, int depth) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StructureNode node : sorted(nodes)) {
            renderNode(node, depth, sb);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private static void renderNode(StructureNode node, int depth, StringBuilder sb) {
        String indent    = " ".repeat(INDENT_SIZE * depth);
        String[] sigLines = splitSignature(node.signature());
        String rowsCell  = formatRows(node.rows());
        String emptyCell = " ".repeat(ROWS_WIDTH);

        for (int i = 0; i < sigLines.length; i++) {
            String cell = (i == 0) ? rowsCell : emptyCell;
            sb.append('|').append(cell)
              .append('|').append(indent).append(sigLines[i])
              .append('\n');
        }

        if (node.children() != null) {
            for (StructureNode child : sorted(node.children())) {
                renderNode(child, depth + 1, sb);
            }
        }
    }

    /**
     * Сортирует узлы по начальному номеру строки из {@code rows}.
     *
     * <p>Правила парсинга:
     * <ul>
     *   <li>{@code "221-223"} → startLine = 221</li>
     *   <li>{@code "217"}     → startLine = 217</li>
     *   <li>{@code null} / нечисловое → {@link Integer#MAX_VALUE} (узел уходит в конец)</li>
     * </ul>
     */
    private static List<StructureNode> sorted(List<StructureNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(StructureNodePrinter::startLine))
                .toList();
    }

    private static int startLine(StructureNode node) {
        String rows = node.rows();
        if (rows == null || rows.isBlank()) {
            return Integer.MAX_VALUE;
        }
        String first = rows.contains("-") ? rows.substring(0, rows.indexOf('-')) : rows;
        try {
            return Integer.parseInt(first.strip());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Форматирует поле rows по правому краю в поле шириной {@link #ROWS_WIDTH}.
     */
    private static String formatRows(String rows) {
        if (rows == null || rows.isBlank()) {
            return " ".repeat(ROWS_WIDTH);
        }
        if (rows.length() >= ROWS_WIDTH) {
            return rows.substring(0, ROWS_WIDTH);
        }
        return String.format("%" + ROWS_WIDTH + "s", rows);
    }

    /**
     * Разбивает сигнатуру на строки по '\n', пустые строки пропускает.
     * Ведущие пробелы убираются — отступ управляется через depth.
     */
    private static String[] splitSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return new String[]{""};
        }
        return signature.lines()
                .filter(l -> !l.isBlank())
                .map(String::stripLeading)
                .toArray(String[]::new);
    }
}
