package com.moga.khronosdemo.dtos.response;

import java.math.BigDecimal;

// Sums over every worker of the report. Costs add up the workers whose cost is known, and are null if none is
public record HoursTotalsResponseDTO(
        long plannedMinutes,
        long workedMinutes,
        long differenceMinutes,
        BigDecimal plannedCost,
        BigDecimal actualCost
) {
}
