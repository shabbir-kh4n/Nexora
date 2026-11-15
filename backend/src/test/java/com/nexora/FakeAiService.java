package com.nexora;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Stands in for the Python service: same path, header, status codes and SSE framing as ai-service/app/runs.py.
final class FakeAiService implements AutoCloseable {

    @FunctionalInterface
    interface Script {
        void run(Call call) throws Exception;
    }

    private static final Pattern RUN_ID = Pattern.compile("\"run_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern QUESTION = Pattern.compile("\"question\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpServer server;
    private final String expectedSecret;
    private volatile Script script;
    private final AtomicInteger eventCounter = new AtomicInteger();

    final AtomicInteger requests = new AtomicInteger();
    final List<String> sent = new CopyOnWriteArrayList<>();
    volatile String lastSecret;
    volatile String lastBody;
    volatile String lastContentType;
    volatile String lastAccept;

    FakeAiService(String expectedSecret, Script script) {
        this.expectedSecret = expectedSecret;
        this.script = script;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/v1/runs", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void script(Script next) {
        this.script = next;
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastSecret = exchange.getRequestHeaders().getFirst("X-Nexora-Internal-Secret");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastAccept = exchange.getRequestHeaders().getFirst("Accept");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody = body;
        Call call = new Call(exchange, find(RUN_ID, body), find(QUESTION, body));
        try {
            if (!expectedSecret.equals(lastSecret)) {
                call.respond(401, "application/json",
                        "{\"error\":{\"code\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Internal authentication failed.\"}}");
                return;
            }
            script.run(call);
        } catch (IOException e) {
            // The client closed the connection; nothing more to send.
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }

    private static String find(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    final class Call {
        private final HttpExchange exchange;
        private OutputStream out;
        final String runId;
        final String question;

        private Call(HttpExchange exchange, String runId, String question) {
            this.exchange = exchange;
            this.runId = runId;
            this.question = question;
        }

        void startStream() throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            out = exchange.getResponseBody();
            out.flush();
        }

        void respond(int status, String contentType, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        // Deterministic ids and timestamps, so a test can prove they pass through unchanged.
        String envelope(String forRunId, String type, String data) {
            int n = eventCounter.incrementAndGet();
            return "{\"version\":\"1\",\"event_id\":\"00000000-0000-4000-8000-%012d\",\"run_id\":\"%s\",\"type\":\"%s\",\"timestamp\":\"2026-11-15T10:00:00.%03dZ\",\"data\":%s}"
                    .formatted(n, forRunId, type, n % 1000, data);
        }

        String event(String type, String data) throws IOException {
            String json = envelope(runId, type, data);
            raw("event: " + type + "\ndata: " + json + "\n\n");
            sent.add(json);
            return json;
        }

        void step(String message) throws IOException {
            event("step", "{\"message\":\"" + message + "\"}");
        }

        void token(String text) throws IOException {
            event("token", "{\"text\":\"" + text + "\"}");
        }

        void raw(String text) throws IOException {
            raw(text.getBytes(StandardCharsets.UTF_8));
        }

        void raw(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        // Writes harmless comment lines until the client goes away; proves the connection was really closed.
        boolean waitForClientToClose(Duration limit) {
            long end = System.nanoTime() + limit.toNanos();
            while (System.nanoTime() < end) {
                try {
                    raw(": keepalive\n\n");
                } catch (IOException e) {
                    return true;
                }
                LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            }
            return false;
        }
    }

    static void completeRun(Call call) throws IOException {
        call.startStream();
        call.step("Planning the research (simulated)");
        call.step("Composing the answer (simulated)");
        for (String text : new String[] {"This ", "is ", "a ", "simulated ", "answer."}) {
            call.token(text);
        }
        call.event("done", "{}");
    }
}
