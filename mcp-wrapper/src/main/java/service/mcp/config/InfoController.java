package service.mcp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Простой info/health-эндпоинт. Deploy-скрипт использует его для проверки готовности (/info).
 */
@RestController
@RequiredArgsConstructor
public class InfoController {

    private final UpstreamProperties upstream;

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "service", "java-class-context-mcp",
                "status", "UP",
                "transport", "SSE",
                "sseEndpoint", "/sse",
                "messageEndpoint", "/mcp/message",
                "upstreamBaseUrl", upstream.baseUrl(),
                "tools", List.of(
                        "create_review_session", "terminate_review_session",
                        "get_structure_json", "get_structure_markdown", "get_structure_html",
                        "get_plantuml_object", "get_plantuml_text",
                        "get_source_file", "get_source_lines_gitlab", "get_source_lines_jar"));
    }
}
