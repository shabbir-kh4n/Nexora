package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NexoraApplicationTests {

    @Value("${spring.threads.virtual.enabled}")
    private boolean virtualThreads;

    @Test
    void contextLoadsWithVirtualThreadsEnabled() {
        assertThat(virtualThreads).isTrue();
    }

    @Test
    void defaultTimeZoneIsKolkata() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Kolkata");
    }
}
