package service.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import service.mcp.tools.SessionTools;
import service.mcp.tools.SourceTools;
import service.mcp.tools.StructureTools;

/**
 * MCP-сервер-обёртка над основным сервисом Java Class Context API.
 *
 * <p>Транспорт — SSE (Spring AI MCP Server поверх Spring MVC). Все 8 REST-эндпоинтов
 * основного сервиса экспонируются как MCP tools и проксируются по HTTP на основной сервис.
 */
@SpringBootApplication
public class McpWrapperApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpWrapperApplication.class, args);
    }

    /**
     * Регистрирует все @Tool-методы трёх групп инструментов в MCP-сервере.
     * Spring AI автоматически опубликует их через MCP-протокол.
     */
    @Bean
    public ToolCallbackProvider javaClassContextTools(SessionTools sessionTools,
                                                      StructureTools structureTools,
                                                      SourceTools sourceTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sessionTools, structureTools, sourceTools)
                .build();
    }
}
