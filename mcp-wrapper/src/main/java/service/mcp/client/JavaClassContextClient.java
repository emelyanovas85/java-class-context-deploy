package service.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import service.mcp.model.SessionDtos.CreateSessionRequest;
import service.mcp.model.SessionDtos.CreateSessionResponse;
import service.mcp.model.SessionDtos.FileSourceRequest;
import service.mcp.model.SessionDtos.GitLabLinesSessionRequest;
import service.mcp.model.SessionDtos.JarLinesRequest;
import service.mcp.model.SessionDtos.PlantUmlSessionRequest;
import service.mcp.model.SessionDtos.SessionIdRequest;
import service.mcp.model.SessionDtos.SessionRequest;

/**
 * Тонкий HTTP-клиент к основному сервису Java Class Context API.
 *
 * <p>Каждый метод проксирует соответствующий REST-эндпоинт основного сервиса.
 * JSON-ответы возвращаются как «сырой» текст ({@code String}), чтобы MCP-инструмент мог
 * передать их LLM без потери структуры. Типизированный ответ используется только для create-сессии,
 * где удобно работать с полями (например, {@code sessionId}).
 *
 * <p>Любой не-2xx ответ основного сервиса транслируется в {@link UpstreamException}
 * с исходным статусом и телом.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaClassContextClient {

    private final RestClient upstreamRestClient;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------------
    // Sessions
    // ---------------------------------------------------------------------

    public CreateSessionResponse createSession(CreateSessionRequest request) {
        log.info("Upstream create session: project='{}', MR !{}", request.projectId(), request.mergeRequestIid());
        return postForObject("/api/review-sessions", request, CreateSessionResponse.class);
    }

    public void terminateSession(String sessionId) {
        log.info("Upstream terminate session: {}", sessionId);
        upstreamRestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/review-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SessionIdRequest(sessionId))
                .retrieve()
                .onStatus(s -> s.isError(), (req, resp) -> {
                    throw toException(resp.getStatusCode().value(), readBody(resp.getBody()));
                })
                .toBodilessEntity();
    }

    // ---------------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------------

    public String structureJson(SessionRequest request) {
        return postForJson("/api/structure/json", request, MediaType.APPLICATION_JSON);
    }

    public String structureHtml(SessionRequest request) {
        return postForJson("/api/structure/html", request, MediaType.TEXT_HTML);
    }

    public String structureMarkdown(SessionRequest request) {
        return postForJson("/api/structure/markdown", request, MediaType.APPLICATION_JSON);
    }

    public String plantUmlObject(PlantUmlSessionRequest request) {
        return postForJson("/api/structure/plantuml/object", request, MediaType.APPLICATION_JSON);
    }

    public String plantUmlText(PlantUmlSessionRequest request) {
        return postForJson("/api/structure/plantuml/text", request, MediaType.TEXT_PLAIN);
    }

    // ---------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------

    public String sourceFile(FileSourceRequest request) {
        return postForJson("/api/source-file", request, MediaType.APPLICATION_JSON);
    }

    public String sourceLinesGitLab(GitLabLinesSessionRequest request) {
        return postForJson("/api/source-lines/gitlab", request, MediaType.APPLICATION_JSON);
    }

    public String sourceLinesJar(JarLinesRequest request) {
        return postForJson("/api/source-lines/jar", request, MediaType.APPLICATION_JSON);
    }

    // ---------------------------------------------------------------------
    // Внутренние помощники
    // ---------------------------------------------------------------------

    private <T> T postForObject(String path, Object body, Class<T> type) {
        return upstreamRestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(s -> s.isError(), (req, resp) -> {
                    throw toException(resp.getStatusCode().value(), readBody(resp.getBody()));
                })
                .body(type);
    }

    private String postForJson(String path, Object body, MediaType accept) {
        return upstreamRestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(accept)
                .body(body)
                .retrieve()
                .onStatus(s -> s.isError(), (req, resp) -> {
                    throw toException(resp.getStatusCode().value(), readBody(resp.getBody()));
                })
                .body(String.class);
    }

    private static String readBody(java.io.InputStream in) {
        try {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private UpstreamException toException(int status, String body) {
        String hint = switch (status) {
            case 400 -> "Ошибка валидации тела запроса основного сервиса.";
            case 404 -> "Сессия не найдена или истёк её TTL — создайте новую сессию.";
            case 410 -> "Сессия терминирована — создайте новую сессию.";
            case 422 -> "MR не в статусе opened/locked — анализ невозможен.";
            case 503 -> "diff_refs ещё не готов или фоновое построение индекса не удалось — повторите позже.";
            default -> "Ошибка обращения к основному сервису.";
        };
        log.warn("Upstream error {}: {} | body={}", status, hint, body);
        return new UpstreamException(status, body,
                "Java Class Context API вернул HTTP " + status + ": " + hint
                        + (body == null || body.isBlank() ? "" : " Детали: " + body));
    }
}
