package ru.kalinin.context.model;

import java.util.Map;

/**
 * Информация об аннотации.
 *
 * @param name       простое имя аннотации, например {@code Service}, {@code Transactional}
 * @param attributes параметры аннотации, например {@code {"value": "\"myBean\""}}
 */
public record AnnotationInfo(
        String name,
        Map<String, String> attributes
) {}
