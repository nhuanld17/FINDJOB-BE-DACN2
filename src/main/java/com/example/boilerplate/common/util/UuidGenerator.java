package com.example.boilerplate.common.util;

import java.util.UUID;

public final class UuidGenerator {

    private UuidGenerator() {}

    public static String generateUuid() {
        return UUID.randomUUID().toString();
    }
}
