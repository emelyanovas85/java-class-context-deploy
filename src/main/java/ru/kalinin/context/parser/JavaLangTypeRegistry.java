package ru.kalinin.context.parser;

import lombok.extern.slf4j.Slf4j;

import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Динамически строит множество simple-имён всех public-типов из пакета {@code java.lang}
 * через Module API (Java 9+), доступный без {@code --add-opens}.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Находим модуль {@code java.base} через {@link ModuleLayer#boot()}.</li>
 *   <li>Через {@link ModuleReference} открываем {@link ModuleReader}.</li>
 *   <li>Перечисляем все ресурсы модуля — берём только
 *       {@code java/lang/Xxx.class} (без {@code $} — top-level типы).</li>
 *   <li>Извлекаем simple name, загружаем класс через {@link Class#forName} и проверяем,
 *       что он public ({@link Class#isPublic()}).</li>
 * </ol>
 *
 * <p>Результат кэшируется в {@code NAMES} — один раз при старте приложения.
 */
@Slf4j
public final class JavaLangTypeRegistry {

    /**
     * Simple names всех public top-level типов java.lang.*
     * (class, interface, enum, record, annotation, exception).
     * Пополняется один раз при любом обращении.
     */
    public static final Set<String> NAMES = buildJavaLangNames();

    private JavaLangTypeRegistry() {}

    private static Set<String> buildJavaLangNames() {
        Set<String> names = new LinkedHashSet<>();

        try {
            // 1. находим java.base в boot layer
            ResolvedModule resolvedModule = ModuleLayer.boot()
                    .configuration()
                    .findModule("java.base")
                    .orElseThrow(() -> new IllegalStateException("Module java.base not found"));

            ModuleReference ref = resolvedModule.reference();

            // 2. читаем все ресурсы модуля
            try (ModuleReader reader = ref.open();
                 Stream<String> resources = reader.list()) {

                resources
                        // только java/lang/Xxx.class (не вложенные — без '/'  в имени файла,
                        //   без '$' — top-level)
                        .filter(r -> r.startsWith("java/lang/")
                                && r.endsWith(".class")
                                && !r.substring("java/lang/".length()).contains("/"))
                                //&& !r.contains("$"))
                        .forEach(r -> {
                            // java/lang/String.class → String
                            String simpleName = r
                                    .substring("java/lang/".length(), r.length() - ".class".length());
                            // загружаем класс и проверяем public
                            try {
                                Class<?> cls = Class.forName("java.lang." + simpleName,
                                        false,
                                        ClassLoader.getSystemClassLoader());
                                if (java.lang.reflect.Modifier.isPublic(cls.getModifiers())) {
                                    names.add(simpleName);
                                }
                            } catch (ClassNotFoundException | LinkageError e) {
                                log.debug("Skipping java.lang.{}: {}", simpleName, e.getMessage());
                            }
                        });
            }

            log.debug("JavaLangTypeRegistry: loaded {} names from java.lang", names.size());

        } catch (Exception e) {
            // Фоллбэк: если Module API недоступен — возвращаем статический минимум
            log.warn("Failed to scan java.lang via Module API, falling back to static list: {}",
                    e.getMessage());
            return STATIC_FALLBACK;
        }

        return Collections.unmodifiableSet(names);
    }

    /**
     * Статический минимум для случая, если Module API недоступен
     * (например, нестандартная JVM).
     */
    private static final Set<String> STATIC_FALLBACK = Set.of(
            "Object", "Enum", "Record", "String", "Number",
            "Integer", "Long", "Double", "Float", "Boolean",
            "Byte", "Short", "Character", "StringBuilder", "StringBuffer",
            "Comparable", "Iterable", "Cloneable", "AutoCloseable", "Runnable",
            "Thread", "ThreadLocal",
            "Throwable", "Error", "Exception", "RuntimeException",
            "ArithmeticException", "ArrayIndexOutOfBoundsException",
            "ArrayStoreException", "ClassCastException",
            "ClassNotFoundException", "CloneNotSupportedException",
            "EnumConstantNotPresentException", "IllegalAccessException",
            "IllegalArgumentException", "IllegalMonitorStateException",
            "IllegalStateException", "IllegalThreadStateException",
            "IndexOutOfBoundsException", "InstantiationException",
            "NegativeArraySizeException", "NoSuchFieldException",
            "NoSuchMethodException", "NullPointerException",
            "NumberFormatException", "SecurityException",
            "StackOverflowError", "StringIndexOutOfBoundsException",
            "TypeNotPresentException", "UnsupportedOperationException",
            "Deprecated", "Override", "SuppressWarnings",
            "FunctionalInterface", "SafeVarargs"
    );
}
