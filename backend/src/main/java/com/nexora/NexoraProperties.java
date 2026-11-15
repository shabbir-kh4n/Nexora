package com.nexora;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// @DefaultValue makes an absent group reach its own validation, which names the missing property.
// Messages name the property, never its value.
@ConfigurationProperties("nexora")
public record NexoraProperties(
        @DefaultValue AiService aiService, @DefaultValue Internal internal, @DefaultValue Run run) {

    public record AiService(URI url) {

        public AiService {
            boolean httpUrl = url != null && url.getHost() != null
                    && ("http".equals(url.getScheme()) || "https".equals(url.getScheme()));
            // Credentials in a URL would leak through toString and binding errors; the secret has its own header.
            require(httpUrl && url.getUserInfo() == null,
                    "nexora.ai-service.url must be an absolute http or https URL without credentials");
        }
    }

    public record Internal(String sharedSecret) {

        public Internal {
            require(sharedSecret != null && !sharedSecret.isBlank(), "nexora.internal.shared-secret must be set");
        }

        @Override
        public String toString() {
            return "Internal[sharedSecret=****]";
        }
    }

    public record Run(
            Duration maxDuration,
            Duration heartbeatInterval,
            Duration upstreamHeartbeatTimeout,
            Duration terminalRetention,
            Integer maxActiveRuns) {

        public Run {
            requirePositive(maxDuration, "nexora.run.max-duration");
            requirePositive(heartbeatInterval, "nexora.run.heartbeat-interval");
            requirePositive(upstreamHeartbeatTimeout, "nexora.run.upstream-heartbeat-timeout");
            requirePositive(terminalRetention, "nexora.run.terminal-retention");
            require(maxActiveRuns != null && maxActiveRuns >= 1, "nexora.run.max-active-runs must be at least 1");
            // A timeout at or below the interval would fail healthy streams between heartbeats.
            require(upstreamHeartbeatTimeout.compareTo(heartbeatInterval) > 0,
                    "nexora.run.upstream-heartbeat-timeout must be greater than nexora.run.heartbeat-interval");
        }

        private static void requirePositive(Duration value, String name) {
            require(value != null && value.isPositive(), name + " must be a positive duration");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
