package ru.kalinin.context.dependency;

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

    @Override
    public String toString() {
        return groupId + ':' + artifactId + ':' + (version != null ? version : "(managed)");
    }
}
