package com.example.boilerplate.features.auth.service;

import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    void register(RegisterRequest request, HttpServletResponse response, String pendingToken);
}
