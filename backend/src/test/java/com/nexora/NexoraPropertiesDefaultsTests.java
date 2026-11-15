package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NexoraPropertiesDefaultsTests {

    @Autowired
    private NexoraProperties properties;

    @Test
    void approvedDefaultsComeFromApplicationYaml() {
        assertThat(properties.aiService().url()).isEqualTo(URI.create("http://127.0.0.1:8000"));
        assertThat(properties.aiService().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.run().maxDuration()).isEqualTo(Duration.ofMinutes(3));
        assertThat(properties.run().heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.run().upstreamHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(40));
        assertThat(properties.run().terminalRetention()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.run().maxActiveRuns()).isEqualTo(10);
    }
}
