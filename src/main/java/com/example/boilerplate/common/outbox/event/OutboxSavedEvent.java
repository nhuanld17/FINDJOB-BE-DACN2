package com.example.boilerplate.common.outbox.event;

import com.example.boilerplate.common.outbox.entity.Outbox;

public record OutboxSavedEvent(
        Long outboxId,
        Outbox outbox
) {
}
