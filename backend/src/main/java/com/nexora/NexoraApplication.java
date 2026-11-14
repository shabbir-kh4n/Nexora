package com.nexora;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexoraApplication {

    static {
        // The JDK may report the legacy alias Asia/Calcutta, which PostgreSQL 18 rejects at connect time.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    public static void main(String[] args) {
        SpringApplication.run(NexoraApplication.class, args);
    }
}
