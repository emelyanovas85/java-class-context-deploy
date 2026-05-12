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
) {}
