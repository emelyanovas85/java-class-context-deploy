package service.structure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * В Spring Boot 4.x {@code RestClient.Builder} не регистрируется автоматически
 * как бин (в отличие от {@code RestTemplate} в Boot 2.x).
 * Этот конфиг явно предоставляет прото-тип-бин {@code RestClient.Builder},
 * чтобы его можно было инжектировать через конструктор (например, в
 * {@code ArtifactorySourcesLoader}).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
