package com.example.boilerplate.features.test.outbox;

import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.event.OutboxSavedEvent;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxTestService {

    private static final List<String> TEST_EMAILS = List.of(
            "hoangquoc@emalupe.com",
            "employee811@emalupe.com",
            "hoangnhat@emalupe.com",
            "quockhanh@emalupe.com",
            "fpt@emalupe.com",
            "mgm@emalupe.com",
            "28honest@web-library.net",
            "user1@emalupe.com",
            "user2@emalupe.com",
            "user3@emalupe.com",
            "user45@emalupe.com",
            "user5@emalupe.com",
            "user6@emalupe.com",
            "user7@emalupe.com",
            "user8@emalupe.com",
            "user9@emalupe.com",
            "user10@emalupe.com",
            "user11@emalupe.com",
            "user13@emalupe.com",
            "user14@emalupe.com",
            "user15@emalupe.com"
    );

    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Map<String, Object> send() {
        log.info("[TEST] Sending {} test emails...", TEST_EMAILS.size());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < TEST_EMAILS.size(); i++) {
            Outbox saved = outboxService.savePending(
                    "EMAIL_GENERIC", "TEST", null,
                    Map.of(
                            "to", TEST_EMAILS.get(i),
                            "templateName", "email/generic",
                            "subject", "Test outbox #" + (i + 1),
                            "htmlContent", "<h1>Test outbox " + (i + 1) + "</h1>"
                    ));
            ids.add(saved.getId());
            log.info("[TEST] Created outbox={} to={}", saved.getId(), TEST_EMAILS.get(i));
            eventPublisher.publishEvent(new OutboxSavedEvent(saved.getId(), saved));
        }
        log.info("[TEST] Done! Created {} outbox(es): {}", ids.size(), ids);
        return Map.of("created", ids.size(), "outboxIds", ids);
    }
}
