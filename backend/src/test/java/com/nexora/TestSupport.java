package com.nexora;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class TestSupport {

    static final String SECRET = "test-only-secret";

    private TestSupport() {
    }

    static NexoraProperties properties(
            URI url, String secret, Duration maxDuration, Duration heartbeatInterval, Duration idleTimeout,
            Duration retention, int maxActive) {
        return new NexoraProperties(
                new NexoraProperties.AiService(url, Duration.ofSeconds(5)),
                new NexoraProperties.Internal(secret),
                new NexoraProperties.Run(maxDuration, heartbeatInterval, idleTimeout, retention, maxActive));
    }

    // Releases every task at the same instant, so each test exercises a real interleaving.
    static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(tasks.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    // A port nothing listens on, for connection-refused cases.
    static URI closedPortUri() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-15T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
