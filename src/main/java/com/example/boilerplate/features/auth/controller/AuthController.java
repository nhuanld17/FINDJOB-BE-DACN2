package com.example.boilerplate.features.auth.controller;

import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody @Valid RegisterRequest request,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String pendingToken
    ) {

        authService.register(request, httpServletResponse, pendingToken);

        return ResponseEntity.ok(null);
    }
}
