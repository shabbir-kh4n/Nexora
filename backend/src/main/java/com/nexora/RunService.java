package com.nexora;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
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
    // Runs that are queued or executing; the only way to reach a run's thread and browser stream.
    private final ConcurrentHashMap<UUID, Execution> executions = new ConcurrentHashMap<>();

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
        Execution execution = new Execution(run, stream);
        executions.put(run.id(), execution);
        try {
            executor.execute(() -> execution.run(question));
        } catch (RejectedExecutionException e) {
            executions.remove(run.id());
            fail(run, stream, ErrorCode.INTERNAL_ERROR);
        }
        return new StartedRun(run, stream);
    }

    public RunSnapshot snapshot(String runId) {
        return find(runId).snapshot();
    }

    // Idempotent: only the call that wins the transition stops anything; every later call just reports the state.
    public RunSnapshot cancel(String runId) {
        Run run = find(runId);
        if (run.cancel()) {
            log.info("Run {} cancelled", run.id());
            Execution execution = executions.get(run.id());
            if (execution != null) {
                execution.cancelled();
            }
        }
        return run.snapshot();
    }

    private Run find(String runId) {
        RunIds.require(runId);
        return registry.find(UUID.fromString(runId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.RUN_NOT_FOUND));
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
        private volatile Thread thread;
        private volatile long lastActivity = System.nanoTime();
        private volatile ScheduledFuture<?> idleCheck;

        Execution(Run run, RunStream stream) {
            this.run = run;
            this.stream = stream;
        }

        void run(String question) {
            // Set before the run can start, so a cancel that wins afterwards always finds the thread.
            thread = Thread.currentThread();
            try {
                execute(question);
            } finally {
                executions.remove(run.id());
            }
        }

        // Ends the browser stream without a terminal event, stops the reader and tells Python to stop.
        void cancelled() {
            stream.close();
            Thread running = thread;
            if (running == null) {
                return;
            }
            running.interrupt();
            try {
                executor.execute(this::cancelUpstream);
            } catch (RejectedExecutionException e) {
                log.warn("Run {} upstream cancel was not sent: executor rejected it", run.id());
            }
        }

        private void cancelUpstream() {
            try {
                client.cancel(run.id());
            } catch (UpstreamException e) {
                log.warn("Run {} upstream cancel failed: {}", run.id(), e.code());
            }
        }

        private void execute(String question) {
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
