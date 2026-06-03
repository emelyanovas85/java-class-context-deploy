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
 *   <li>Многострочная сигнатура разбивается на части: каждой строке
 *       присваивается свой номер, начиная с {@code startLine}.
 *       Последняя строка сигнатуры получает оставшийся диапазон
 *       ({@code "lastStart"} или {@code "lastStart-end"} если end > lastStart).
 *       Пустых ячеек (emptyCell) не остаётся.</li>
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
        int n = sigLines.length;

        int[] range = parseRange(node.rows());  // [start, end], или null

        for (int i = 0; i < n; i++) {
            String cell;
            if (range == null) {
                // нет диапазона — только первая строка получает исходный rows (или пусто)
                cell = (i == 0) ? formatRows(node.rows()) : " ".repeat(ROWS_WIDTH);
            } else {
                int start = range[0];
                int end   = range[1];
                int lineNo = start + i;
                if (i == n - 1) {
                    // последняя строка сигнатуры — остаток диапазона
                    cell = formatRows(lineNo == end ? String.valueOf(lineNo)
                                                    : lineNo + "-" + end);
                } else {
                    cell = formatRows(String.valueOf(lineNo));
                }
            }
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
     * Парсит {@code rows} в массив {@code [start, end]}.
     * {@code "17"} → {@code [17, 17]}, {@code "19-22"} → {@code [19, 22]}.
     * Возвращает {@code null} если строка пустая или не числовая.
     */
    private static int[] parseRange(String rows) {
        if (rows == null || rows.isBlank()) return null;
        try {
            int dash = rows.indexOf('-');
            if (dash < 0) {
                int v = Integer.parseInt(rows.strip());
                return new int[]{v, v};
            }
            int start = Integer.parseInt(rows.substring(0, dash).strip());
            int end   = Integer.parseInt(rows.substring(dash + 1).strip());
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            return null;
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
        int[] range = parseRange(node.rows());
        return range != null ? range[0] : Integer.MAX_VALUE;
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
