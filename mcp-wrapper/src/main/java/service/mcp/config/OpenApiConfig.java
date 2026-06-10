package service.mcp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Java Class Context MCP Server")
                        .description("""
                                MCP-сервер-обёртка над основным сервисом **Java Class Context API**.

                                Сервер реализован на Spring Boot + Spring AI и публикует все REST-эндпоинты
                                основного сервиса (структурный анализ Java-классов по GitLab Merge Request)
                                как **MCP tools**. Транспорт — **SSE** (Server-Sent Events).

                                ## Подключение MCP-клиента
                                - SSE endpoint: `GET /sse`
                                - Message endpoint: `POST /mcp/message`

                                ## Доступные tools
                                - `create_review_session`, `terminate_review_session` — сессии
                                - `get_structure_json`, `get_structure_markdown`, `get_structure_html`,
                                  `get_plantuml_object`, `get_plantuml_text` — структура и диаграммы
                                - `get_source_file`, `get_source_lines_gitlab`, `get_source_lines_jar` — исходники

                                Все запросы (кроме `create_review_session` и `get_source_lines_jar`) требуют `sessionId`.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("java-class-context-mcp")
                                .url("https://github.com/emelyanovas85/java-class-context-deploy"))
                        .license(new License()
                                .name("MIT")));
    }
}
