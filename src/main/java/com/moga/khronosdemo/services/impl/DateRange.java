package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.exceptions.InvalidRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The optional from and to dates of a listing, both inclusive. A missing date leaves that side open.
 */
record DateRange(LocalDate from, LocalDate to) {

    DateRange {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
    }

    // Reports need both dates and cover at most maxDays days, counting both ends
    static DateRange bounded(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null) {
            throw new InvalidRequestException("'from' and 'to' are required");
        }
        DateRange range = new DateRange(from, to);
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new InvalidRequestException("The date range cannot be longer than " + maxDays + " days");
        }
        return range;
    }

    LocalDateTime startInclusive() {
        return from != null ? from.atStartOfDay() : null;
    }

    // Midnight after the last day, so everything that happens on 'to' is still included
    LocalDateTime endExclusive() {
        return to != null ? to.plusDays(1).atStartOfDay() : null;
    }
}
