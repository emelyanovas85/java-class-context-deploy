package service.mcp.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import service.mcp.client.JavaClassContextClient;
import service.mcp.model.SessionDtos.CreateSessionRequest;
import service.mcp.model.SessionDtos.CreateSessionResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP-инструменты группы Sessions — жизненный цикл сессии ревью MR.
 */
@Slf4j
@Component
public class SessionTools {

    private final JavaClassContextClient client;
    private final ObjectMapper objectMapper;

    public SessionTools(JavaClassContextClient client,
                        @Qualifier("jacksonJsonMapper") ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "create_review_session", description = """
            Создать сессию ревью GitLab Merge Request. Это ПЕРВЫЙ шаг любого анализа:
            возвращает sessionId, который нужно передавать во все последующие инструменты
            (структура, исходники, диаграммы). Загружает MR, фиксирует sourceSha/targetSha
            и строит merged file index в фоне (последующие work-запросы дождутся его готовности).
            Токен GitLab передаётся ТОЛЬКО ЗДЕСЬ; дальше он не нужен.
            Повторный вызов для того же MR терминирует предыдущую сессию.
            Возвращает JSON: sessionId, sourceSha, targetSha, baseSha, expiresAt.
            """)
    public String createReviewSession(
            @ToolParam(description = "URL GitLab-инстанса без trailing slash, например https://gitlab.com")
            String gitlabUrl,
            @ToolParam(description = "ID проекта (число) или путь namespace/project, например mygroup/myproject")
            String projectId,
            @ToolParam(description = "Personal/Project Access Token с правами read_api, read_repository (glpat-...)")
            String token,
            @ToolParam(description = "IID мёрж-реквеста — внутренний номер в проекте (для !42 укажите 42)")
            Long mergeRequestIid
    ) {
        CreateSessionResponse response = client.createSession(
                new CreateSessionRequest(gitlabUrl, projectId, token, mergeRequestIid));
        return toJson(response);
    }

    @Tool(name = "terminate_review_session", description = """
            Терминировать сессию ревью: отменяет незавершённые построения контекста и удаляет
            данные сессии. Идемпотентно — успешно завершается, даже если сессия уже отсутствует
            или её TTL истёк. Вызывайте при обновлении MR или по завершении работы.
            """)
    public String terminateReviewSession(
            @ToolParam(description = "sessionId из ответа create_review_session")
            String sessionId
    ) {
        client.terminateSession(sessionId);
        return "{\"status\":\"terminated\",\"sessionId\":\"" + sessionId + "\"}";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("Failed to serialize response to JSON", e);
            return String.valueOf(value);
        }
    }
}
