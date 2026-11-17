package com.moga.khronosdemo.dtos.response;

import java.math.BigDecimal;
import java.time.LocalDate;

// The period before the summary's, to compare with: worked out exactly like the summary's totals
public record SummaryPeriodResponseDTO(
        LocalDate from,
        LocalDate to,
        BigDecimal sales,
        BigDecimal expenses,
        BigDecimal laborCost,
        BigDecimal grossMargin
) {
}
