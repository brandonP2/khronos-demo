package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.exceptions.InvalidRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The optional from and to dates of a listing, both inclusive. A missing date leaves that side open.
 */
record DateRange(LocalDate from, LocalDate to) {

    DateRange {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
    }

    LocalDateTime startInclusive() {
        return from != null ? from.atStartOfDay() : null;
    }

    // Midnight after the last day, so everything that happens on 'to' is still included
    LocalDateTime endExclusive() {
        return to != null ? to.plusDays(1).atStartOfDay() : null;
    }
}
