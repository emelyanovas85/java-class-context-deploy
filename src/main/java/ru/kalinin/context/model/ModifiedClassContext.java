package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Контекст класса, структура которого изменилась: source и target различаются,
 * либо одна из веток null (файл создан или удалён).
 *
 * @param name             qualified name класса
 * @param level            уровень контекста
 * @param structureSource  структура source-ветки (null — файл удалён)
 * @param structureTarget  структура target-ветки (null — файл создан)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Класс с изменениями: source и target различаются")
public record ModifiedClassContext(
        String name,
        int level,
        List<StructureNode> structureSource,
        List<StructureNode> structureTarget
) implements ChangedClassContext {

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
