package com.example.boilerplate.features.test.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/test/outbox")
@RequiredArgsConstructor
public class OutboxTestController {

    private final OutboxTestService outboxTestService;

    @PostMapping("/send")
    public Map<String, Object> send() {
        log.info("[TEST] POST /api/test/outbox/send called");
        return outboxTestService.send();
    }
}

