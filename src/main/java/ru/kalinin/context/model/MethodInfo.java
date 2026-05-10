package ru.kalinin.context.model;

import java.util.List;

/**
 * Информация о методе или конструкторе.
 *
 * @param annotations     аннотации метода
 * @param modifiers       модификаторы: public, static и т.д.
 * @param returnType      возвращаемый тип (null для конструктора)
 * @param name            имя метода
 * @param typeParameters  типовые параметры метода, например {@code ["T extends Comparable<T>"]}
 * @param parameters      параметры
 * @param thrownExceptions бросаемые исключения
 * @param isConstructor   true, если это конструктор
 */
public record MethodInfo(
        List<AnnotationInfo> annotations,
        List<String> modifiers,
        String returnType,
        String name,
        List<String> typeParameters,
        List<ParameterInfo> parameters,
        List<String> thrownExceptions,
        boolean isConstructor
) {}
