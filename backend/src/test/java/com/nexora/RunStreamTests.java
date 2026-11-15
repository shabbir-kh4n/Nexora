package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexora.RunHarness.RecordingEmitter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RunStreamTests {

    private static RecordingEmitter attached(RunStream stream) {
        RecordingEmitter emitter = new RecordingEmitter();
        stream.attach(emitter);
        return emitter;
    }

    @Test
    void framesReachTheBrowserInOrderAndTheEmitterCompletesAfterTheTerminalFrame() throws Exception {
        RunStream stream = new RunStream();
        RecordingEmitter emitter = attached(stream);

        stream.send("step", "{\"n\":1}");
        stream.send("token", "{\"n\":2}");
        stream.sendTerminal("done", "{\"n\":3}");

        assertThat(emitter.awaitCompleted()).isTrue();
        assertThat(emitter.frames).containsExactly(
                new SseParser.Frame("step", "{\"n\":1}"),
                new SseParser.Frame("token", "{\"n\":2}"),
                new SseParser.Frame("done", "{\"n\":3}"));
    }

    @Test
    void nothingIsAcceptedAfterTheTerminalFrameAndASecondTerminalIsIgnored() throws Exception {
        RunStream stream = new RunStream();
        RecordingEmitter emitter = attached(stream);

        stream.sendTerminal("error", "{\"first\":true}");
        stream.send("token", "{\"late\":true}");
        stream.sendTerminal("done", "{\"late\":true}");

        assertThat(emitter.awaitCompleted()).isTrue();
        assertThat(emitter.frames).containsExactly(new SseParser.Frame("error", "{\"first\":true}"));
    }

    @Test
    void framesSentBeforeTheBrowserAttachesAreDeliveredWhenItDoes() throws Exception {
        RunStream stream = new RunStream();
        stream.send("step", "{\"early\":true}");
        stream.sendTerminal("done", "{}");

        RecordingEmitter emitter = attached(stream);

        assertThat(emitter.awaitCompleted()).isTrue();
        assertThat(emitter.frames).extracting(SseParser.Frame::event).containsExactly("step", "done");
    }

    @Test
    void aFailingBrowserConnectionStopsDeliveryWithoutAnythingEscaping() throws Exception {
        RunStream stream = new RunStream();
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failSends = true;
        stream.attach(emitter);

        stream.send("step", "{}");
        stream.send("token", "{}");
        stream.sendTerminal("done", "{}");

        assertThat(emitter.frames).isEmpty();
    }

    @Test
    void aBrowserTimeoutCompletesTheEmitterAndStopsDelivery() throws Exception {
        RunStream stream = new RunStream();
        RecordingEmitter emitter = attached(stream);
        stream.send("step", "{\"n\":1}");
        await().atMost(Duration.ofSeconds(10)).until(() -> emitter.frames.size() == 1);

        emitter.fireTimeout();
        stream.send("token", "{\"late\":true}");
        stream.sendTerminal("done", "{}");

        assertThat(emitter.awaitCompleted()).isTrue();
        assertThat(emitter.frames).containsExactly(new SseParser.Frame("step", "{\"n\":1}"));
    }

    @Test
    void sendingWithNoBrowserAtAllNeverFails() {
        RunStream stream = new RunStream();

        stream.send("step", "{}");
        stream.sendTerminal("done", "{}");
        stream.send("token", "{}");
    }
}
