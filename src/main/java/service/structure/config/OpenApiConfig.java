package service.structure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Java Class Context API")
                        .description("""
                                Сервис анализа структуры Java-классов по мёрж-реквесту GitLab.

                                Возвращает сигнатуры классов (поля, методы, вложенные классы,
                                аннотации) для всех файлов, изменённых в MR, с заданной
                                глубиной контекста зависимостей.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("java-class-context")
                                .url("https://github.com/KalininAY/java-class-context"))
                        .license(new License()
                                .name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")));
    }
}
