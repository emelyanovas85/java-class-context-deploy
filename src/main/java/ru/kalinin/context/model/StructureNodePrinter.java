package ru.kalinin.context.model;

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
 * </ul>
 *
 * <p>Пример вывода:
 * <pre>
 * ### path/to/File.java
 * |     23-88|@SomeAnnotation
 * |          |public class MyClass extends BaseClass
 * |        25|    private final String name
 * |     66-87|    public static class InnerClass
 * |     68-77|        @Order
 * |          |        public String withName(String n)
 * </pre>
 */
public final class StructureNodePrinter {

    /** Ширина колонки с номерами строк (между двумя '|') */
    private static final int ROWS_WIDTH = 10;
    /** Размер одного уровня отступа */
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
        for (StructureNode node : nodes) {
            renderNode(node, depth, sb);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private static void renderNode(StructureNode node, int depth, StringBuilder sb) {
        String indent = " ".repeat(INDENT_SIZE * depth);
        String[] sigLines = splitSignature(node.signature());
        String rowsCell = formatRows(node.rows());
        String emptyCell  = " ".repeat(ROWS_WIDTH);

        for (int i = 0; i < sigLines.length; i++) {
            String cell = (i == 0) ? rowsCell : emptyCell;
            sb.append('|').append(cell)
              .append('|').append(indent).append(sigLines[i])
              .append('\n');
        }

        // рекурсия для дочерних узлов
        if (node.children() != null) {
            for (StructureNode child : node.children()) {
                renderNode(child, depth + 1, sb);
            }
        }
    }

    /**
     * Форматирует поле rows по правому краю в поле шириной {@link #ROWS_WIDTH}.
     * Если {@code rows} null или пусто — возвращает строку из пробелов.
     */
    private static String formatRows(String rows) {
        if (rows == null || rows.isBlank()) {
            return " ".repeat(ROWS_WIDTH);
        }
        int width = Math.max(rows.length(), ROWS_WIDTH);
        return String.format("%" + width + "s", rows);
    }

    /**
     * Разбивает сигнатуру на строки по символу '\n'.
     * Пустые строки и строки только из пробелов пропускаются.
     * Если сигнатура null или пуста — возвращает массив с одной пустой строкой.
     */
    private static String[] splitSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return new String[]{""};
        }
        return signature.lines()
                .filter(l -> !l.isBlank())
                // убираем ведущие пробелы из самой сигнатуры —
                // отступ управляется через depth/indent
                .map(String::stripLeading)
                .toArray(String[]::new);
    }
}
