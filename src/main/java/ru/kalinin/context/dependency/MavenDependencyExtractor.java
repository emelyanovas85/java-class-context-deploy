package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Заготовка для извлечения зависимостей из {@code pom.xml}.
 *
 * <p>Реализация будет добавлена позднее.
 * На данный момент метод {@link #extract(String)} бросает
 * {@link UnsupportedOperationException}.
 */
@Slf4j
@Component
public class MavenDependencyExtractor implements DependencyExtractor {

    @Override
    public boolean supports(String fileName) {
        return "pom.xml".equals(fileName);
    }

    @Override
    public List<DependencyCoordinate> extract(String fileContent) {
        // TODO: разобрать <dependencies> из XML, учесть <dependencyManagement>
        throw new UnsupportedOperationException(
                "MavenDependencyExtractor is not implemented yet");
    }
}
