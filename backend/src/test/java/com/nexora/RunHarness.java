package com.nexora;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// The real service, registry and client wired by hand, with the timing values a test needs.
final class RunHarness implements AutoCloseable {

    final RunRegistry registry;
    final RunService service;
    final JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    RunHarness(URI url, String secret, Duration maxDuration, Duration interval, Duration idle, int maxActive) {
        this(url, secret, maxDuration, interval, idle, maxActive, null);
    }

    RunHarness(URI url, String secret, Duration maxDuration, Duration interval, Duration idle, int maxActive,
            Executor executorOverride) {
        NexoraProperties properties =
                TestSupport.properties(url, secret, maxDuration, interval, idle, Duration.ofHours(1), maxActive);
        RunExecutionConfig config = new RunExecutionConfig();
        Clock clock = Clock.systemUTC();
        executor = config.runExecutor();
        scheduler = config.runScheduler();
        registry = new RunRegistry(maxActive, Duration.ofHours(1), clock);
        service = new RunService(properties, registry, new AiServiceClient(properties), mapper, clock,
                executorOverride != null ? executorOverride : executor, scheduler);
    }

    static RunHarness defaults(URI url) {
        return new RunHarness(url, TestSupport.SECRET, Duration.ofSeconds(30), Duration.ofMillis(100),
                Duration.ofSeconds(10), 10);
    }

    int pendingTimers() {
        return ((java.util.concurrent.ScheduledThreadPoolExecutor) scheduler).getQueue().size();
    }

    static void awaitTerminal(Run run) {
        await().atMost(Duration.ofSeconds(20)).until(() -> run.status().isTerminal());
    }

    @Override
    public void close() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    // Captures what would be written to a browser, using the real parser on the real frame text.
    static final class RecordingEmitter extends SseEmitter {
        final List<SseParser.Frame> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        volatile boolean failSends;
        private volatile Runnable timeoutCallback;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (failSends) {
                throw new IOException("client gone");
            }
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                text.append(part.getData());
            }
            byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            new SseParser(frame -> frames.add(frame)).feed(bytes, 0, bytes.length);
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            timeoutCallback = callback;
            super.onTimeout(callback);
        }

        // What the servlet container does when the emitter's timeout elapses.
        void fireTimeout() {
            timeoutCallback.run();
        }

        @Override
        public synchronized void complete() {
            completed.countDown();
        }

        boolean awaitCompleted() throws InterruptedException {
            return completed.await(15, TimeUnit.SECONDS);
        }
    }
}
