package com.moga.khronosdemo.dtos.response;

import com.moga.khronosdemo.entities.enums.LaborCostSource;

import java.math.BigDecimal;

public record SummaryTotalsResponseDTO(
        // From the cash closings of the range, drafts included
        BigDecimal sales,
        BigDecimal cashSales,
        BigDecimal cardSales,
        BigDecimal otherSales,
        BigDecimal tips,
        // Every expense of the range
        BigDecimal expenses,
        // The PAYROLL part of those expenses
        BigDecimal payrollExpenses,
        // Hours worked times the rate in force each day, as in the hours report; null when no worker's cost is known
        BigDecimal laborCost,
        // Hours of the shifts times the rate; null when no worker's cost is known
        BigDecimal plannedLaborCost,
        // sales - (expenses - payrollExpenses) - the staff cost: laborCost, or payrollExpenses when laborCost is
        // null (see laborCostSource); negative when the period lost money
        BigDecimal grossMargin,
        // Which staff cost grossMargin took off: HOURS (laborCost) or PAYROLL_EXPENSES (laborCost is null)
        LaborCostSource laborCostSource,
        // laborCost / sales x 100, one decimal; null when sales are 0 or laborCost is unknown
        BigDecimal laborCostPercent,
        // The differences of the range's closings added up
        BigDecimal cashDifference,
        int closedDays,
        int draftDays,
        // Days before today with a shift or a time entry and no closing
        int missingClosingDays
) {
}
