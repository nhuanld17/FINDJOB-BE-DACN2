package com.example.boilerplate.common.exception;

import org.springframework.security.core.AuthenticationException;

public class AccountBannedException extends AuthenticationException {
    public AccountBannedException(String msg) { super(msg); }
}
