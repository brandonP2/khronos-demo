package com.moga.khronosdemo.dtos.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequestDTO(
        @NotBlank @Size(min = 8, max = 100)
        String newPassword
) {
}
