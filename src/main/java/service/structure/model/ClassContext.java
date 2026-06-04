package service.structure.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Сеалед иерархия контекста класса в рамках мёрж-реквеста.
 *
 * <p>Два варианта:
 * <ul>
 *   <li>{@link UnchangedClassContext} — source и target структуры идентичны,
 *       хранится одно поле {@code structureTarget}.</li>
 *   <li>{@link ModifiedClassContext} — структуры различаются либо одна из них null
 *       (файл создан/удалён/изменён).</li>
 * </ul>
 *
 * <p>Каждый экземпляр несёт сквозной {@link #id()} и множество {@link #callerIds()} —
 * id классов, которые непосредственно ссылаются на данный. Классы уровня 0
 * имеют пустой {@code callerIds}.
 *
 * <h3>Поле module</h3>
 * <p>Описывает, откуда был получен класс:
 * <ul>
 *   <li>{@code "main"} — из {@code src/main/java/} репозитория</li>
 *   <li>{@code "test"} — из {@code src/test/java/} репозитория</li>
 *   <li>{@code "groupId:artifactId:version"} — из sources.jar внешней зависимости,
 *       например {@code "org.aspectj:aspectjweaver:1.9.22"}</li>
 * </ul>
 *
 * <p>Используйте фабрику {@link #of} вместо прямого создания.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UnchangedClassContext.class, name = "unchanged"),
        @JsonSubTypes.Type(value = ModifiedClassContext.class, name = "modified")
})
public sealed interface ClassContext
        permits UnchangedClassContext, ModifiedClassContext {

    /** Сквозной уникальный идентификатор класса в рамках одного ответа. */
    int id();

    /** qualified name класса, например {@code com.example.Foo}. */
    String name();

    /** Уровень контекста: 0 = изменён в MR, 1+ = зависимость. */
    int level();

    /**
     * Идентификаторы классов, которые непосредственно ссылаются на данный.
     * Пустое множество для классов уровня 0 (изменённые файлы MR).
     */
    Set<Integer> callerIds();

    /**
     * Источник класса:
     * {@code "main"}, {@code "test"} или
     * {@code "groupId:artifactId:version"} для внешних зависимостей.
     */
    String module();

    /**
     * Фабрика: сравнивает структуры и возвращает подходящий подтип.
     *
     * <ul>
     *   <li>Если оба не null и равны — {@link UnchangedClassContext}.</li>
     *   <li>Иначе — {@link ModifiedClassContext}.</li>
     * </ul>
     *
     * @param id               сквозной id
     * @param callerIds        id классов-потребителей
     * @param name             qualified name
     * @param level            уровень контекста
     * @param source           источник: "main", "test" или "groupId:artifactId:version"
     * @param structureSource  структура source-ветки (null — файл удалён)
     * @param structureTarget  структура target-ветки (null — файл создан)
     */
    static ClassContext of(int id, Set<Integer> callerIds,
                           String name, int level,
                           String source,
                           List<StructureNode> structureSource,
                           List<StructureNode> structureTarget) {
        if (structureSource != null
                && structureTarget != null
                && Objects.equals(structureSource, structureTarget)) {
            return new UnchangedClassContext(id, name, level, callerIds, source, structureSource);
        }
        return new ModifiedClassContext(id, name, level, callerIds, source, structureSource, structureTarget);
    }
}
