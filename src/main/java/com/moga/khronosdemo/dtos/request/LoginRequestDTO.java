package com.moga.khronosdemo.dtos.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequestDTO(
        @NotBlank
        String username,
        @NotBlank
        String password
) {
}
