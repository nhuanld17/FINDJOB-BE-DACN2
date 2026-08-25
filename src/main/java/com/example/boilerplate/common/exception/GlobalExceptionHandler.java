package com.example.boilerplate.common.exception;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid fail on @RequestBody
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, List<String>> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(fieldError -> {
            String resolved = resolveValidationMessage(fieldError);
            errors.computeIfAbsent(fieldError.getField(), k -> new ArrayList<>());
            errors.get(fieldError.getField()).add(resolved);
        });

        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.ofValidation(errors));
    }

    // @Valid fail on @RequestParam / @PathVariable
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, List<String>> errors = new HashMap<>();

        ex.getConstraintViolations().forEach(violation -> {
            String field = violation.getPropertyPath().toString();
            // Dùng resolveEnumKey thay vì raw message
            String resolved = resolveEnumKey(
                    violation.getMessage(),
                    violation.getConstraintDescriptor().getAttributes()
            );
            errors.computeIfAbsent(field, k -> new ArrayList<>());
            errors.get(field).add(resolved);
        });

        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.ofValidation(errors));
    }

    // Body không đọc được: JSON sai cú pháp, sai kiểu dữ liệu (vd UUID sai định dạng)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();

        // Sai kiểu 1 field cụ thể (vd deviceId không phải UUID hợp lệ)
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            Class<?> targetType = ife.getTargetType();

            String message = targetType != null && UUID.class.isAssignableFrom(targetType)
                    ? "Invalid UUID format"
                    : "Invalid value for type '%s'".formatted(
                            targetType != null ? targetType.getSimpleName() : "unknown");

            Map<String, List<String>> errors = new HashMap<>();
            errors.put(Objects.requireNonNullElse(field, "body"),
                    new ArrayList<>(List.of(message)));
            return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(errors));
        }

        // JSON hỏng / rỗng / không parse được
        log.warn("Malformed request body: {}", cause.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.of(400, "Malformed request body"));
    }

    // AppException — business logic errors
    @ExceptionHandler(AppException.class)
    public ResponseEntity<?> handleAppException(AppException ex) {
        log.warn("AppException [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatusCode())
                .body(ErrorResponse.of(
                        errorCode.getHttpStatusCode().value(), // 404, 409, 401...
                        errorCode.getCode(),               // 2001, 2002, 3001...
                        ex.getMessage()
                ));
    }

    // Wrong data type on @PathVariable / @RequestParam (ex: pass "abc" into Long id)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String typeName = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";

        String message = "Parameter '%s' must be of type '%s'"
                .formatted(ex.getName(), typeName);

        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.of(400, message));
    }

    // Missing required @RequestParam
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = "Missing parameter '%s'".formatted(ex.getParameterName());

        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.of(400, message));
    }

    // 401 - Not login yet
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> handleAuthenticationException(AuthenticationException ex) {
        // ✅ Không expose ex.getMessage() — dùng message từ ErrorCode
        // ⚠️ Fix Bug 1: dùng overload of(status, code, message) — trước đây truyền
        // business code (3001) vào tham số status khiến body có status=3001, code=null.
        ErrorCode errorCode = ErrorCode.UNAUTHENTICATED;
        return ResponseEntity
                .status(errorCode.getHttpStatusCode())
                .body(ErrorResponse.of(
                        errorCode.getHttpStatusCode().value(),
                        errorCode.getCode(),
                        errorCode.getMessage()
                ));
    }

    // 403 - Access Denied
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDeniedException(AccessDeniedException ex) {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        return ResponseEntity
                .status(errorCode.getHttpStatusCode())
                .body(ErrorResponse.of(
                        errorCode.getHttpStatusCode().value(),
                        errorCode.getCode(),
                        errorCode.getMessage()
                ));
    }

    // 404 — URL does not exist
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity
                .status(404)
                .body(ErrorResponse.of(404, "Resource not found"));
    }

    // 405 — Wrong HTTP method
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        String message = "Method '%s' is not allowed for this endpoint"
                .formatted(ex.getMethod());

        return ResponseEntity
                .status(405)
                .body(ErrorResponse.of(405, message));
    }

    // Fallback — All undefined errors, log but not expose detail to client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception ex) {
        log.error("Unhandled exception: ", ex);
        // Dùng ErrorCode thay vì hardcode 500 — dùng overload đủ 3 tham số
        // (trước đây truyền business code 9999 vào tham số status → body status=9999, code=null)
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(errorCode.getHttpStatusCode())
                .body(ErrorResponse.of(
                        errorCode.getHttpStatusCode().value(),
                        errorCode.getCode(),
                        errorCode.getMessage()
                ));
    }

    // ===== Helpers =====

    private String resolveValidationMessage(org.springframework.validation.FieldError fieldError) {
        String raw = fieldError.getDefaultMessage();

        // raw phải là 1 key của ErrorCode (vd: "INVALID_PASSWORD"); nếu không thì trả thẳng
        try {
            ErrorCode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Non-enum validation message: '{}'", raw);
            return raw;
        }

        // Lấy attributes của constraint (min, max, ...) để thay thế placeholder {min}, {max}
        Map<String, Object> attributes = Collections.emptyMap();
        try {
            ConstraintViolation<?> violation = fieldError.unwrap(ConstraintViolation.class);
            attributes = violation.getConstraintDescriptor().getAttributes();
        } catch (Exception ignored) {
            // Không unwrap được ConstraintViolation -> replace được placeholder nào thì replace nấy
        }

        // Truyền KEY (raw), không phải message đã resolve — resolveEnumKey tự valueOf và thay placeholder
        return resolveEnumKey(raw, attributes);
    }

    private String resolveEnumKey(String raw, Map<String, Object> attributes) {
        try {
            ErrorCode errorCode = ErrorCode.valueOf(raw);
            String message = errorCode.getMessage();
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
            return message;
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
}
