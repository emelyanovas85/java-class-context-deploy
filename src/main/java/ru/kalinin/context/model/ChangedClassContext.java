package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Контекст одного класса в рамках мёрж-реквеста.
 *
 * @param name            qualified name класса, например {@code com.example.Foo}
 * @param level           уровень контекста: 0 = изменён в MR, 1+ = зависимость
 * @param structureSource структура класса в source-ветке MR (null — файл новый)
 * @param structureTarget структура класса в target-ветке MR (null — файл удалён или новый)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangedClassContext(
        String name,
        int level,
        List<StructureNode> structureSource,
        List<StructureNode> structureTarget
) {
    /**
     * Псевдо-Markdown представление контекста класса.
     *
     * <p>Пример:
     * <pre>
     * ### path/to/File.java  [level=0, source]
     * |  23-88   |@SomeAnnotation
     * |          |public class MyClass
     * </pre>
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = "### " + name + "  [level=" + level + "]";

        if (structureSource != null) {
            sb.append(header).append("  [source]\n");
            sb.append(StructureNodePrinter.render(structureSource, 0));
        }
        if (structureTarget != null) {
            sb.append(header).append("  [target]\n");
            sb.append(StructureNodePrinter.render(structureTarget, 0));
        }
        return sb.toString();
    }
}
