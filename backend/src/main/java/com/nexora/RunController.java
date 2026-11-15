package com.nexora;

import java.time.Duration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runs")
class RunController {

    // Longer than the run deadline, so the browser stream outlives any run; a timeout never touches the run.
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(4);

    private final RunService runService;

    RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    SseEmitter create(@RequestBody RunRequest request) {
        RunService.StartedRun started = runService.start(request.question());
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        started.stream().attach(emitter);
        return emitter;
    }
}
