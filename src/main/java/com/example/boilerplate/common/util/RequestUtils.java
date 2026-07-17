package com.example.boilerplate.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestUtils {

    /**
     * Lấy ip của 1 request
     * @param request
     * @return
     */
    public String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Lấy ra user agent của 1 request
     * @param request
     * @return
     */
    public String getUserAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
