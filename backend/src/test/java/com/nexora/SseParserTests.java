package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseParserTests {

    private static List<SseParser.Frame> parse(byte[] bytes, int chunk) {
        List<SseParser.Frame> frames = new ArrayList<>();
        SseParser parser = new SseParser(frame -> frames.add(frame));
        for (int i = 0; i < bytes.length; i += chunk) {
            parser.feed(bytes, i, Math.min(chunk, bytes.length - i));
        }
        return frames;
    }

    private static List<SseParser.Frame> parse(String text, int chunk) {
        return parse(text.getBytes(StandardCharsets.UTF_8), chunk);
    }

    private static final String TWO_EVENTS = "event: step\ndata: {\"a\":1}\n\nevent: token\ndata: {\"b\":2}\n\n";

    @Test
    void parsesSeveralEventsDeliveredInOneRead() {
        assertThat(parse(TWO_EVENTS, 1000)).containsExactly(
                new SseParser.Frame("step", "{\"a\":1}"), new SseParser.Frame("token", "{\"b\":2}"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 7, 13})
    void theResultDoesNotDependOnHowTheBytesAreSplit(int chunk) {
        assertThat(parse(TWO_EVENTS, chunk)).isEqualTo(parse(TWO_EVENTS, 1000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void everyLineEndingIsAccepted(String eol) {
        String text = "event: step" + eol + "data: x" + eol + eol + "event: done" + eol + "data: y" + eol + eol;

        for (int chunk : new int[] {1, 4, 1000}) {
            assertThat(parse(text, chunk))
                    .containsExactly(new SseParser.Frame("step", "x"), new SseParser.Frame("done", "y"));
        }
    }

    @Test
    void aCrLfSplitBetweenReadsIsOneLineBreakNotTwo() {
        String text = "event: a\r\ndata: 1\r\n\r\nevent: b\r\ndata: 2\r\n\r\n";

        assertThat(parse(text, 1)).hasSize(2);
    }

    @Test
    void multiByteCharactersSplitAcrossReadsSurvive() {
        String data = "café 日本語 😀 end";

        List<SseParser.Frame> frames = parse("event: token\ndata: " + data + "\n\n", 1);

        assertThat(frames).containsExactly(new SseParser.Frame("token", data));
    }

    @Test
    void commentsAndUnknownFieldsAreIgnoredAndHeartbeatsParse() {
        String text = ": keepalive\nid: 7\nretry: 100\nevent: heartbeat\ndata: {}\n\n: tail\n\n";

        assertThat(parse(text, 3)).containsExactly(new SseParser.Frame("heartbeat", "{}"));
    }

    @Test
    void aSpaceAfterTheColonIsOptionalAndOnlyOneIsRemoved() {
        assertThat(parse("event:a\ndata:b\n\n", 5)).containsExactly(new SseParser.Frame("a", "b"));
        assertThat(parse("event: a\ndata:  b\n\n", 5)).containsExactly(new SseParser.Frame("a", " b"));
    }

    @Test
    void multipleDataLinesAreJoinedWithANewline() {
        assertThat(parse("event: a\ndata: one\ndata: two\n\n", 4)).containsExactly(new SseParser.Frame("a", "one\ntwo"));
    }

    @Test
    void anEventWithoutDataIsDroppedAndItsNameDoesNotLeakIntoTheNext() {
        assertThat(parse("event: lonely\n\nevent: b\ndata: 1\n\n", 6)).containsExactly(new SseParser.Frame("b", "1"));
        assertThat(parse("event: lonely\n\ndata: 1\n\n", 6)).containsExactly(new SseParser.Frame(null, "1"));
    }

    @Test
    void extraBlankLinesBetweenEventsAreHarmless() {
        assertThat(parse("\n\nevent: a\ndata: 1\n\n\n\n\nevent: b\ndata: 2\n\n", 3)).hasSize(2);
    }

    @Test
    void anIncompleteTrailingEventIsNeverDelivered() {
        assertThat(parse("event: a\ndata: 1\n\nevent: b\ndata: 2", 4)).containsExactly(new SseParser.Frame("a", "1"));
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] bad = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};

        assertThatThrownBy(() -> parse(bad, 100)).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_STREAM_INVALID));
    }

    @Test
    void anOversizedLineIsRejectedInsteadOfBufferedForever() {
        byte[] huge = ("data: " + "x".repeat((1 << 20) + 10)).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parse(huge, 8192)).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_STREAM_INVALID));
    }

    @Test
    void theSinkCanStopTheParser() {
        List<SseParser.Frame> frames = new ArrayList<>();
        SseParser parser = new SseParser(frame -> {
            frames.add(frame);
            return false;
        });
        byte[] bytes = TWO_EVENTS.getBytes(StandardCharsets.UTF_8);

        assertThat(parser.feed(bytes, 0, bytes.length)).isFalse();
        assertThat(frames).hasSize(1);
    }
}
