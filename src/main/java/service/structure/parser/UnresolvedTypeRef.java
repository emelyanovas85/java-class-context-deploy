package service.structure.parser;

import java.util.List;

/**
 * Ссылка на тип, которая могла остаться нерезолвленной после парсинга
 * (например, из-за wildcard-импорта {@code import pkg.*}).
 *
 * <p>Содержит:
 * <ul>
 *   <li>{@code name} — имя типа как оно сохранено в {@link service.structure.model.ClassStructure}:
 *       либо уже qualified ({@code io.qameta.allure.Description}),
 *       либо simple ({@code Description}) если wildcard-resolver не смог резолвить при парсинге.</li>
 *   <li>{@code wildcardPackages} — wildcard-пакеты файла, из которого пришёл тип.
 *       Используются для пост-резолвинга: если {@code name} — simple name,
 *       а среди уже известных qualified типов есть {@code pkg.name} где {@code pkg ∈ wildcardPackages},
 *       то тип однозначно резолвится.</li>
 * </ul>
 */
public record UnresolvedTypeRef(String name, List<String> wildcardPackages) {

    /**
     * Возвращает true, если имя выглядит как simple name (не содержит точки).
     * Fully-qualified имена (уже резолвленные) не нуждаются в пост-резолвинге.
     */
    public boolean isUnresolved() {
        return !name.contains(".");
    }
}
