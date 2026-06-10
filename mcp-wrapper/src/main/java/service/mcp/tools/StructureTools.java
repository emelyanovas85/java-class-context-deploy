package service.mcp.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import service.mcp.client.JavaClassContextClient;
import service.mcp.model.SessionDtos.PlantUmlSessionRequest;
import service.mcp.model.SessionDtos.SessionRequest;

import java.util.List;

/**
 * MCP-инструменты группы Structure — построение структурного контекста Java-классов по сессии.
 *
 * <p>Во всех инструментах параметр {@code names} опционален: если не задан (null), основной сервис
 * анализирует все изменённые .java-файлы MR. Передаётся в API только при наличии значений —
 * пустой список заменяется на null, чтобы не получить 400.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructureTools {

    private final JavaClassContextClient client;

    @Tool(name = "get_structure_json", description = """
            Построить структурный контекст изменённых классов MR в виде JSON (ContextResponse):
            метаданные MR, список FileContext (классы сравнены source vs target), requestedDepth,
            totalClassesAnalyzed. Требует активную сессию.
            depth=0 — только корневые файлы/классы; depth=N — BFS-обход зависимостей на N уровней
            (repo + sources.jar). names опционально: без него анализируются все изменённые .java MR;
            с ним — конкретные классы (simple/qualified имя или путь src/.../Foo.java).
            """)
    public String getStructureJson(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Глубина BFS: 0 — только корни; N>0 — + зависимости repo и jar")
            int depth,
            @ToolParam(required = false, description =
                    "Опционально. Корни обхода: simple/qualified имена или пути src/.../Foo.java. "
                            + "Не указывайте (или пустой список) — анализируются все изменённые файлы MR.")
            List<String> names
    ) {
        return client.structureJson(new SessionRequest(sessionId, depth, normalize(names)));
    }

    @Tool(name = "get_structure_markdown", description = """
            То же, что get_structure_json, но ответ — JSON-массив markdown-строк (по одному FileContext
            на файл). Удобно для передачи в LLM без парсинга дерева JSON. Требует активную сессию.
            depth и names — как в get_structure_json (names опционально).
            """)
    public String getStructureMarkdown(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Глубина BFS: 0 — только корни; N>0 — + зависимости")
            int depth,
            @ToolParam(required = false, description =
                    "Опционально. Корни обхода. Не указывайте — все изменённые файлы MR.")
            List<String> names
    ) {
        return client.structureMarkdown(new SessionRequest(sessionId, depth, normalize(names)));
    }

    @Tool(name = "get_structure_html", description = """
            То же, что get_structure_json, но ответ — HTML-страница для визуального просмотра деревьев
            структур (отладка). Параметры идентичны. Требует активную сессию.
            names опционально: без него — все изменённые файлы MR.
            """)
    public String getStructureHtml(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Глубина BFS: 0 — только корни; N>0 — + зависимости")
            int depth,
            @ToolParam(required = false, description =
                    "Опционально. Корни обхода. Не указывайте — все изменённые файлы MR.")
            List<String> names
    ) {
        return client.structureHtml(new SessionRequest(sessionId, depth, normalize(names)));
    }

    @Tool(name = "get_plantuml_object", description = """
            Построить PlantUML class diagram по контексту MR и вернуть JSON-обёртку (PlantUmlResponse):
            текст диаграммы plantUml + метаданные MR и счётчики. Требует активную сессию.
            depth и names — как в get_structure_json (names опционально).
            pretty=true (по умолчанию) — читаемые отступы; false — компактный вывод.
            """)
    public String getPlantUmlObject(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Глубина BFS: 0 — только корни; N>0 — + зависимости")
            int depth,
            @ToolParam(required = false, description =
                    "Опционально. Корни обхода. Не указывайте — все изменённые файлы MR.")
            List<String> names,
            @ToolParam(required = false, description = "Форматирование: true (по умолчанию) — отступы; false — компактно")
            Boolean pretty
    ) {
        return client.plantUmlObject(new PlantUmlSessionRequest(sessionId, depth, normalize(names), pretty));
    }

    @Tool(name = "get_plantuml_text", description = """
            Аналог get_plantuml_object, но тело ответа — только текст PlantUML (text/plain), без JSON-обёртки.
            Удобно для копирования в plantuml.com или рендерер. Требует активную сессию.
            depth и names — как в get_structure_json (names опционально). pretty — как выше.
            """)
    public String getPlantUmlText(
            @ToolParam(description = "sessionId из create_review_session")
            String sessionId,
            @ToolParam(description = "Глубина BFS: 0 — только корни; N>0 — + зависимости")
            int depth,
            @ToolParam(required = false, description =
                    "Опционально. Корни обхода. Не указывайте — все изменённые файлы MR.")
            List<String> names,
            @ToolParam(required = false, description = "Форматирование: true (по умолчанию) — отступы; false — компактно")
            Boolean pretty
    ) {
        return client.plantUmlText(new PlantUmlSessionRequest(sessionId, depth, normalize(names), pretty));
    }

    /** Пустой/отсутствующий список превращаем в null — основной сервис трактует это как «все файлы MR». */
    private static List<String> normalize(List<String> names) {
        return (names == null || names.isEmpty()) ? null : names;
    }
}
