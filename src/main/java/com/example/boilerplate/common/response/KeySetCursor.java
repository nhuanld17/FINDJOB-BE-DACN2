package com.example.boilerplate.common.response;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Tiện ích mã hoá / giải mã CURSOR cho keyset pagination.
 *
 * Cursor = base64url("epochMillis:id")  — vd: "1783000000000:42"
 *   - epochMillis: created_at (Instant) của ITEM CUỐI cùng đã trả
 *   - id:          id của item cuối (tie-breaker — đảm bảo duy nhất)
 *
 * Vì sao dùng cả (createdAt, id)? createdAt có thể trùng giữa 2 job;
 * id luôn duy nhất → cặp (createdAt, id) sắp xếp toàn bộ, không bao giờ hoà.
 */
public final class KeySetCursor {

    private KeySetCursor() {}

    /**
     * Mốc keyset: (createdAt, id) của item cuối cùng
     */
    public record Cursor(Instant createdAt, Long id) { }

    /**
     * Mã hóa mốc thành chuỗi an toàn trong url
     */
    public static String encode(Instant createdAt, Long id) {
        // Dùng toEpochMilli() (vd: "1783000000000") chứ KHÔNG dùng toString()
        // (vd: "2026-08-06T12:00:00Z") — vì toString() chứa dấu ':'
        // làm split(":") trong decode() ra > 2 phần → lỗi decode.
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Giải mã mốc -> createdAt:id. null/blank (trang đầu). Sai format -> AppException
     */
    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");

            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor format: " + cursor);
            }

            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
        } catch (Exception ex) {
            throw new AppException(ErrorCode.BLANK_FIELD);
        }
    }
}
