package com.example.boilerplate.features.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test")
public class PingController {
    @GetMapping("/ping")
    public ResponseEntity<?> ping(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.example.boilerplate.infrastructure.security.CustomUserDetails user) {
        return ResponseEntity.ok(java.util.Map.of("username", user.getUsername()));
    }
}
