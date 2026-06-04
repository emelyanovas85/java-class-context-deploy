package service.structure.dependency;

/**
 * Maven-координаты одной зависимости.
 *
 * @param groupId    например {@code org.springframework.boot}
 * @param artifactId например {@code spring-boot-starter-web}
 * @param version    явно указанная версия, либо {@code null} если управляется BOM
 */
public record DependencyCoordinate(
        String groupId,
        String artifactId,
        String version
) {

    /**
     * Разделитель между groupId, artifactId и version в имени файла на диске.
     * Двойное подчёркивание ({@code __}) устраняет неоднозначность, возникающую при
     * использовании одинарного {@code -}, поскольку groupId, artifactId или version сами
     * могут содержать дефисы.
     */
    static final String SEPARATOR = "__";

    /** Возвращает {@code true}, если версия явно задана. */
    public boolean hasVersion() {
        return version != null && !version.isBlank();
    }

    /** Относительный Maven-путь: {@code org/springframework/boot/spring-boot-starter-web/3.4.1/} */
    public String mavenRelativePath() {
        return groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/';
    }

    /** Имя *-sources.jar артефакта: {@code spring-boot-starter-web-3.4.1-sources.jar} */
    public String sourcesJarName() {
        return artifactId + '-' + version + "-sources.jar";
    }

    /** Имя Gradle Module Metadata файла: {@code spring-boot-starter-web-3.4.1.module} */
    public String moduleFileName() {
        return artifactId + '-' + version + ".module";
    }

    /**
     * Имя файла на диске для sources.jar:
     * {@code groupId__artifactId__version-sources.jar}.
     *
     * <p>Дочерние и дефисы внутри каждого сегмента сохраняются без замены —
     * они допустимы во всех основных ФС.
     *
     * <p>Пример: {@code org.aspectj__aspectjweaver__1.9.22-sources.jar}
     */
    public String localFileName() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version + "-sources.jar";
    }

    /**
     * Имя файла на диске для .module:
     * {@code groupId__artifactId__version.module}.
     *
     * <p>Пример: {@code org.aspectj__aspectjweaver__1.9.22.module}
     */
    public String localModuleFileName() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version + ".module";
    }

    /**
     * Парсит имя файла в координаты. Ожидаемый формат:
     * {@code groupId__artifactId__version-sources.jar}.
     *
     * @param fileName имя файла (без пути)
     * @return парсенные координаты, либо {@code null} если формат не соответствует
     */
    public static DependencyCoordinate fromLocalFileName(String fileName) {
        String withoutSuffix = fileName.endsWith("-sources.jar")
                ? fileName.substring(0, fileName.length() - "-sources.jar".length())
                : fileName.endsWith(".jar")
                ? fileName.substring(0, fileName.length() - ".jar".length())
                : null;
        if (withoutSuffix == null) return null;

        String[] parts = withoutSuffix.split(SEPARATOR, 3);
        if (parts.length != 3) return null;

        return new DependencyCoordinate(parts[0], parts[1], parts[2]);
    }

    @Override
    public String toString() {
        return groupId + ':' + artifactId + ':' + (version != null ? version : "(managed)");
    }
}
