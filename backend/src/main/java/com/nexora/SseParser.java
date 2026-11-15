package com.nexora;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

// Splits on line breaks before decoding, so a multi-byte character cut across reads is never decoded half-way.
final class SseParser {

    record Frame(String event, String data) {
    }

    private static final int MAX_BYTES = 1 << 20;

    private final Predicate<Frame> sink;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private String event;
    private boolean skipLineFeed;

    // The sink returns false to stop reading, for example after a terminal event.
    SseParser(Predicate<Frame> sink) {
        this.sink = sink;
    }

    // Returns false once the sink has asked to stop.
    boolean feed(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = bytes[i];
            if (skipLineFeed) {
                skipLineFeed = false;
                if (b == '\n') {
                    continue;
                }
            }
            if (b == '\n' || b == '\r') {
                skipLineFeed = b == '\r';
                if (!endOfLine()) {
                    return false;
                }
            } else {
                if (line.size() >= MAX_BYTES) {
                    throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID);
                }
                line.write(b);
            }
        }
        return true;
    }

    private boolean endOfLine() {
        String text = decode(line.toByteArray());
        line.reset();
        if (text.isEmpty()) {
            return dispatch();
        }
        if (text.charAt(0) == ':') {
            return true;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? "" : text.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if (field.equals("event")) {
            event = value;
        } else if (field.equals("data")) {
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
            if (data.length() > MAX_BYTES) {
                throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID);
            }
        }
        return true;
    }

    private boolean dispatch() {
        Frame frame = hasData ? new Frame(event, data.toString()) : null;
        event = null;
        data.setLength(0);
        hasData = false;
        return frame == null || sink.test(frame);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID, e);
        }
    }
}
