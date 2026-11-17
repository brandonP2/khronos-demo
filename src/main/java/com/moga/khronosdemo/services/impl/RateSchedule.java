package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.entities.WorkerRate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One worker's hourly rates by the day they start. The rate for a day is the one with the latest
 * start on or before it.
 */
final class RateSchedule {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final NavigableMap<LocalDate, BigDecimal> rates;

    private RateSchedule(NavigableMap<LocalDate, BigDecimal> rates) {
        this.rates = rates;
    }

    static RateSchedule none() {
        return new RateSchedule(new TreeMap<>());
    }

    static RateSchedule of(Collection<WorkerRate> workerRates) {
        NavigableMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        workerRates.forEach(rate -> rates.put(rate.getEffectiveFrom(), rate.getHourlyRate()));
        return new RateSchedule(rates);
    }

    static Map<Long, RateSchedule> byWorker(Collection<WorkerRate> workerRates) {
        return workerRates.stream()
                .collect(Collectors.groupingBy(rate -> rate.getWorker().getUserId(),
                        Collectors.collectingAndThen(Collectors.toList(), RateSchedule::of)));
    }

    BigDecimal rateOn(LocalDate day) {
        Map.Entry<LocalDate, BigDecimal> rate = rates.floorEntry(day);
        return rate != null ? rate.getValue() : null;
    }

    /**
     * The cost of the given minutes per day, each day at the rate in force that day, rounded to cents
     * at the end. Null when the worker has no rate, or when some minutes fall on a day before their
     * first rate, because the cost would then be understated.
     */
    BigDecimal costOf(Map<LocalDate, Long> minutesByDay) {
        if (rates.isEmpty()) {
            return null;
        }
        BigDecimal rateTimesMinutes = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, Long> day : minutesByDay.entrySet()) {
            if (day.getValue() == 0) {
                continue;
            }
            BigDecimal rate = rateOn(day.getKey());
            if (rate == null) {
                return null;
            }
            rateTimesMinutes = rateTimesMinutes.add(rate.multiply(BigDecimal.valueOf(day.getValue())));
        }
        return rateTimesMinutes.divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
    }
}
