package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExchangeTicketRequest (
        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 1, max = 200, message = "OUT_OF_SIZE")
        String ticket
){
}
