package service.structure.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import service.structure.session.ReviewSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Caffeine-кэш сессий и индекс активной сессии по MR. */
@Configuration
public class ReviewSessionConfig {

    /** In-memory store сессий с {@code expireAfterWrite}. */
    @Bean
    public Cache<String, ReviewSession> reviewSessionCache(
            @Value("${app.review-session.ttl-minutes:120}") int ttlMinutes) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .build();
    }

    /** Ключ MR → текущий {@code sessionId} (для supersede при create). */
    @Bean
    public ConcurrentHashMap<String, String> activeSessionByMrKey() {
        return new ConcurrentHashMap<>();
    }
}
