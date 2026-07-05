package com.example.boilerplate.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestUtils {

    public String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public String getUserAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
