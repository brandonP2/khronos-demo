package com.moga.khronosdemo.dtos.response;

import java.time.Instant;

public record TokenResponseDTO(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        long expiresIn
) {
}
