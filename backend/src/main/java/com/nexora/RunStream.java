package com.nexora;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Decouples the browser connection from the run: the run only enqueues, a separate thread writes to the
// browser, so a slow or vanished client can never stall or fail the run.
final class RunStream {

    private record Frame(String type, String json, boolean terminal) {
    }

    private static final Frame WAKE = new Frame("", "", true);
    private static final Frame END = new Frame("", "", true);

    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final Object monitor = new Object();
    private volatile boolean detached;

    void send(String type, String json) {
        enqueue(new Frame(type, json, false));
    }

    // The relay stops at the first terminal frame, so anything queued after it is never delivered.
    void sendTerminal(String type, String json) {
        enqueue(new Frame(type, json, true));
    }

    // Ends the browser stream without a terminal event, which is how a cancellation looks to the client.
    void close() {
        enqueue(END);
    }

    void attach(SseEmitter emitter) {
        emitter.onCompletion(this::detach);
        emitter.onError(error -> detach());
        emitter.onTimeout(() -> {
            detach();
            emitter.complete();
        });
        Thread.ofVirtual().name("run-relay").start(() -> drain(emitter));
    }

    private void enqueue(Frame frame) {
        synchronized (monitor) {
            if (!detached) {
                queue.offer(frame);
            }
        }
    }

    private void detach() {
        synchronized (monitor) {
            detached = true;
            queue.clear();
            queue.offer(WAKE);
        }
    }

    private void drain(SseEmitter emitter) {
        try {
            while (true) {
                Frame frame = queue.take();
                if (detached) {
                    return;
                }
                if (frame != END) {
                    emitter.send(SseEmitter.event().name(frame.type()).data(frame.json()));
                }
                if (frame.terminal()) {
                    emitter.complete();
                    return;
                }
            }
        } catch (IOException | IllegalStateException e) {
            // The browser went away; the run carries on without it.
            detach();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detach();
        }
    }
}
