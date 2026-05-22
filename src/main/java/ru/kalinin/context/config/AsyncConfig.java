package ru.kalinin.context.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Конфигурация пула потоков для параллельных I/O-операций.
 *
 * <p>Используются виртуальные потоки Java 21 (Project Loom):
 * идеально подходят для I/O-bound задач (HTTP к GitLab/Artifactory),
 * не блокируют carrier threads и не требуют ручного sizing пула.
 */
@Configuration
public class AsyncConfig {

    /**
     * Executor на виртуальных потоках для параллельного чтения файлов из GitLab.
     * Используется в {@link ru.kalinin.context.service.ContextBuilderService}.
     */
    @Bean(name = "ioExecutor", destroyMethod = "shutdown")
    public ExecutorService ioExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
