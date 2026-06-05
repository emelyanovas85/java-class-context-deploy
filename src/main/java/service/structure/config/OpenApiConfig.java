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
                                Сервис структурного анализа Java-классов по **GitLab Merge Request** с сессией ревью и pin коммитов.

                                ## Быстрый старт

                                1. **Sessions** → `POST /api/review-sessions` — один раз передать GitLab credentials и MR.
                                   Получить `sessionId` и зафиксированные SHA.
                                2. **Structure** / **Sources** → все work-запросы только с `sessionId` (credentials не нужны).
                                3. **Sessions** → `DELETE /api/review-sessions` — завершить сессию, когда MR обновился или работа закончена.

                                ## Параметры structure-запросов

                                | Поле | Описание |
                                |------|----------|
                                | `depth` | `0` — только корневые файлы/классы; `N` — BFS по зависимостям на N уровней (repo + sources.jar) |
                                | `names` | Опционально. Корни обхода: simple/qualified имя или `src/.../Foo.java`. Без поля — все **изменённые** `.java` в MR. Сначала repo-индекс, затем jar-зависимости |

                                ## Коды ошибок

                                | HTTP | Когда |
                                |------|-------|
                                | 404 | Сессия не найдена / истёк TTL |
                                | 410 | Сессия терминирована (в т.ч. во время построения) |
                                | 400 | Валидация; `names` не найдены |
                                | 422 | MR не `opened`/`locked` (только create) |
                                | 503 | GitLab ещё не заполнил `diff_refs` (только create) |

                                Группы API: **Sessions**, **Structure**, **Sources**.
                                """)
                        .version("2.0.0")
                        .contact(new Contact()
                                .name("java-class-context")
                                .url("https://github.com/KalininAY/java-class-context"))
                        .license(new License()
                                .name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")));
    }
}
