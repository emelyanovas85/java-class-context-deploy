package ru.kalinin.context.model;

import java.util.List;

/**
 * Структура Java-класса (class, interface, enum, record, @interface).
 *
 * @param annotations      аннотации на классе
 * @param modifiers        модификаторы: public, abstract и т.д.
 * @param kind             тип: class, interface, enum, record, @interface
 * @param name             простое имя
 * @param qualifiedName    полное имя с пакетом
 * @param typeParameters   типовые параметры класса, например {@code ["T", "E extends Comparable<E>"]}
 * @param extendedType     родительский класс (null если отсутствует)
 * @param implementedTypes реализуемые интерфейсы
 * @param fields           поля / record-компоненты
 * @param methods          методы и конструкторы
 * @param nestedClasses    вложенные классы (nested types)
 * @param sourceFile       путь к файлу в репозитории
 * @param contextLevel     уровень контекста: 0 = изменённый MR-файл, 1+ = зависимость
 * @param wildcardImports  wildcard-пакеты из импортов файла (например {@code ["io.qameta.allure"]}),
 *                         используются для пост-резолвинга нерезолвленных simple-имён
 */
public record ClassStructure(
        List<AnnotationInfo> annotations,
        List<String> modifiers,
        String kind,
        String name,
        String qualifiedName,
        List<String> typeParameters,
        String extendedType,
        List<String> implementedTypes,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<ClassStructure> nestedClasses,
        String sourceFile,
        int contextLevel,
        List<String> wildcardImports
) {}
