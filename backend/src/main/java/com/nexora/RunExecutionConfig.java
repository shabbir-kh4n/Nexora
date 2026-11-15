package com.nexora;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RunExecutionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // shutdownNow so stopping the application interrupts runs instead of waiting out their deadline.
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService runExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("run-", 0).factory());
    }

    // Timer tasks only flip state and interrupt, so one thread is enough; cancelled tasks are dropped at once.
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService runScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "run-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    RunRegistry runRegistry(NexoraProperties properties, Clock clock) {
        NexoraProperties.Run run = properties.run();
        return new RunRegistry(run.maxActiveRuns(), run.terminalRetention(), clock);
    }
}
