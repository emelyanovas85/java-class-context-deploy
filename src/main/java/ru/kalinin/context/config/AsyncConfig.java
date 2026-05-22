package ru.kalinin.context.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Конфигурация пула потоков для параллельных I/O-операций.
 *
 * <p>Используется fixed thread pool с размером, ориентированным на I/O-bound задачи
 * (HTTP к GitLab/Artifactory). Размер пула = 2 × число процессоров — стандартная
 * эвристика для задач с блокирующим I/O.
 */
@Configuration
public class AsyncConfig {

    /**
     * Executor для параллельного чтения файлов из GitLab.
     * Используется в {@link ru.kalinin.context.service.ContextBuilderService}.
     */
    @Bean(name = "ioExecutor", destroyMethod = "shutdown")
    public ExecutorService ioExecutor() {
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        return Executors.newFixedThreadPool(threads);
    }
}
