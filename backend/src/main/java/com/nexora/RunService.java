package com.nexora;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    public record StartedRun(Run run, RunStream stream) {
    }

    private final NexoraProperties.Run limits;
    private final RunRegistry registry;
    private final AiServiceClient client;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;

    public RunService(
            NexoraProperties properties,
            RunRegistry registry,
            AiServiceClient client,
            JsonMapper mapper,
            Clock clock,
            @Qualifier("runExecutor") Executor executor,
            @Qualifier("runScheduler") ScheduledExecutorService scheduler) {
        this.limits = properties.run();
        this.registry = registry;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    // Returns as soon as the run is registered and handed to the executor; the run outlives the caller.
    public StartedRun start(String question) {
        Run run = new Run(UUID.randomUUID(), clock);
        RunRegistry.Registration registration = registry.register(run);
        if (registration == RunRegistry.Registration.DUPLICATE) {
            throw new IllegalStateException("Run id already registered");
        }
        RunStream stream = new RunStream();
        if (registration == RunRegistry.Registration.AT_CAPACITY) {
            // Failed before it can start, so it takes no slot and never reaches the AI service.
            fail(run, stream, ErrorCode.RUN_CAPACITY_EXCEEDED);
            registry.register(run);
            return new StartedRun(run, stream);
        }
        try {
            executor.execute(() -> new Execution(run, stream).run(question));
        } catch (RejectedExecutionException e) {
            fail(run, stream, ErrorCode.INTERNAL_ERROR);
        }
        return new StartedRun(run, stream);
    }

    // Only the caller whose transition wins reports the failure, so a run emits at most one terminal event.
    private boolean fail(Run run, RunStream stream, ErrorCode code) {
        if (!run.fail(code)) {
            return false;
        }
        log.warn("Run {} failed: {}", run.id(), code);
        stream.sendTerminal("error", errorEnvelope(run, code));
        return true;
    }

    private String errorEnvelope(Run run, ErrorCode code) {
        ObjectNode data = mapper.createObjectNode().put("code", code.name()).put("message", code.message());
        RunEvent event = new RunEvent(
                "1", UUID.randomUUID().toString(), run.id().toString(), RunEvent.Type.ERROR, clock.instant(), data);
        return mapper.writeValueAsString(event);
    }

    private final class Execution {

        private final Run run;
        private final RunStream stream;
        private final Thread thread = Thread.currentThread();
        private volatile long lastActivity = System.nanoTime();
        private volatile ScheduledFuture<?> idleCheck;

        Execution(Run run, RunStream stream) {
            this.run = run;
            this.stream = stream;
        }

        void run(String question) {
            if (!run.start()) {
                return;
            }
            ScheduledFuture<?> deadline = null;
            try {
                deadline = scheduler.schedule(
                        () -> abort(ErrorCode.RUN_TIMEOUT), limits.maxDuration().toNanos(), TimeUnit.NANOSECONDS);
                scheduleIdleCheck(limits.upstreamHeartbeatTimeout().toNanos());
                client.stream(run.id(), question, () -> lastActivity = System.nanoTime(), this::onFrame);
                // A stream that ends without a terminal event is truncated; this is a no-op after done or error.
                fail(run, stream, ErrorCode.UPSTREAM_STREAM_TRUNCATED);
            } catch (UpstreamException e) {
                fail(run, stream, e.code());
            } catch (RuntimeException e) {
                log.warn("Run {} hit an unexpected {}", run.id(), e.getClass().getSimpleName());
                fail(run, stream, ErrorCode.INTERNAL_ERROR);
            } finally {
                cancel(deadline);
                cancel(idleCheck);
                fail(run, stream, ErrorCode.INTERNAL_ERROR);
            }
        }

        private static void cancel(ScheduledFuture<?> timer) {
            if (timer != null) {
                timer.cancel(false);
            }
        }

        // Interrupting unblocks the reader, which is otherwise stuck waiting for upstream bytes.
        private void abort(ErrorCode code) {
            if (fail(run, stream, code)) {
                thread.interrupt();
            }
        }

        private void scheduleIdleCheck(long timeoutNanos) {
            idleCheck = scheduler.schedule(() -> {
                long idle = System.nanoTime() - lastActivity;
                if (idle >= timeoutNanos) {
                    abort(ErrorCode.UPSTREAM_STREAM_TRUNCATED);
                } else if (!run.status().isTerminal()) {
                    scheduleIdleCheck(timeoutNanos - idle);
                }
            }, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        private boolean onFrame(SseParser.Frame frame) {
            RunEvent event = decode(frame);
            boolean applied;
            switch (event.type()) {
                case STEP -> applied = run.addStep(event.data().get("message").stringValue());
                case TOKEN -> applied = run.appendOutput(event.data().get("text").stringValue());
                case HEARTBEAT -> applied = !run.status().isTerminal();
                case DONE -> {
                    if (run.complete()) {
                        stream.sendTerminal(frame.event(), frame.data());
                    }
                    return false;
                }
                case ERROR -> {
                    if (run.fail(ErrorCode.valueOf(event.data().get("code").stringValue()))) {
                        stream.sendTerminal(frame.event(), frame.data());
                    }
                    return false;
                }
                default -> throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID);
            }
            if (applied) {
                stream.send(frame.event(), frame.data());
            }
            // Not applied means the run ended elsewhere, so there is nothing left to read for.
            return applied;
        }

        private RunEvent decode(SseParser.Frame frame) {
            RunEvent event;
            try {
                event = mapper.readValue(frame.data(), RunEvent.class);
            } catch (RuntimeException e) {
                throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID, e);
            }
            boolean consistent = event.type().wireName().equals(frame.event())
                    && event.runId().equals(run.id().toString());
            if (!consistent) {
                throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID);
            }
            return event;
        }
    }
}
