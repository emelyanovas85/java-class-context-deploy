package service.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP-сервер-обёртка над основным сервисом Java Class Context API.
 *
 * <p>Транспорт — Streamable HTTP (Spring AI MCP Server поверх Spring MVC).
 * Все 8 REST-эндпоинтов основного сервиса экспонируются как MCP tools
 * и проксируются по HTTP на основной сервис.
 *
 * <p>MCP-инструменты регистрируются в {@link service.mcp.config.McpToolsConfig}.
 */
@SpringBootApplication
public class McpWrapperApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpWrapperApplication.class, args);
    }
}
