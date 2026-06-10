package service.mcp.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import service.mcp.client.JavaClassContextClient;
import service.mcp.model.SessionDtos.ClassLines;
import service.mcp.model.SessionDtos.FileSourceRequest;
import service.mcp.model.SessionDtos.GitLabLinesSessionRequest;
import service.mcp.model.SessionDtos.JarLinesRequest;
import service.mcp.model.SessionDtos.SessionIdRequest;

import java.util.List;

/**
 * MCP-инструменты группы Sources — получение исходного кода: полные файлы и фрагменты по строкам.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceTools {

    private final JavaClassContextClient client;

    @Tool(name = "get_source_file", description = """
            Получить ПОЛНЫЙ текст .java-файлов по списку имён классов. Поиск идёт в merged repo index
            и в sources.jar зависимостей; по каждому имени возвращаются все совпадения (simple name
            может дать несколько файлов). Требует активную сессию. Возвращает JSON FileSourceResponse.
            """)
    public String getSourceFile(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Simple или qualified имена классов/файлов, например [\"UserService\", \"com.example.Foo\"]. Обязателен (минимум один).")
            List<String> names
    ) {
        return client.sourceFile(new FileSourceRequest(sessionId, names));
    }

    @Tool(name = "get_source_lines_gitlab", description = """
            Получить конкретные ДИАПАЗОНЫ СТРОК из .java-файлов репозитория MR (по pinned sourceSha сессии).
            Credentials не нужны — только sessionId. Требует активную сессию.
            Для каждого класса укажите qualifiedName, опционально source ('main'/'test' для disambiguation)
            и rows — диапазоны строк в формате "28-168" (включительно) или "17" (одна строка).
            Возвращает JSON SourceLinesResponse.
            """)
    public String getSourceLinesGitLab(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = """
                    Список классов с диапазонами строк. Каждый элемент: qualifiedName (обязательно),
                    source ('main'/'test', опционально), rows (список диапазонов вида "28-168" или "17").""")
            List<ClassLines> classes
    ) {
        return client.sourceLinesGitLab(
                new GitLabLinesSessionRequest(new SessionIdRequest(sessionId), classes));
    }

    @Tool(name = "get_source_lines_jar", description = """
            Получить диапазоны строк из локального *-sources.jar внешней зависимости по Maven-координатам.
            СЕССИЯ НЕ ТРЕБУЕТСЯ. source — groupId:artifactId:version (как поле module в ClassContext из
            structure). Для каждого класса: qualifiedName + rows (диапазоны "17" или "19-22").
            Возвращает JSON SourceLinesResponse.
            """)
    public String getSourceLinesJar(
            @ToolParam(description = "Maven-координаты зависимости в формате groupId:artifactId:version, например org.aspectj:aspectjweaver:1.9.22")
            String source,
            @ToolParam(description = """
                    Список классов с диапазонами строк. Каждый элемент: qualifiedName (обязательно)
                    и rows (список диапазонов вида "17" или "19-22"). Поле source внутри элемента не используется.""")
            List<ClassLines> classes
    ) {
        return client.sourceLinesJar(new JarLinesRequest(source, classes));
    }
}
