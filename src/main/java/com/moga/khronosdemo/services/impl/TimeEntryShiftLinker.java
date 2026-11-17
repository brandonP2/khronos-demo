package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.config.TimeTrackingProperties;
import com.moga.khronosdemo.entities.TimeEntry;
import com.moga.khronosdemo.entities.WorkerShift;
import com.moga.khronosdemo.repositories.TimeEntryRepository;
import com.moga.khronosdemo.repositories.WorkerShiftRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Matches time entries to planned shifts. Shifts are planned in local time, so the clock-in instant is
 * first turned into a local date-time with the establishment's time zone.
 */
@Component
public class TimeEntryShiftLinker {

    private final WorkerShiftRepository workerShiftRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final TimeTrackingProperties properties;

    public TimeEntryShiftLinker(WorkerShiftRepository workerShiftRepository, TimeEntryRepository timeEntryRepository,
                                TimeTrackingProperties properties) {
        this.workerShiftRepository = workerShiftRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.properties = properties;
    }

    /**
     * Links the entry to the worker's shift whose interval, widened by the match window (3 hours) before
     * its start, contains the clock-in, and sets the business date to that shift's date. A clock-in at
     * 00:30 on Saturday during a Friday 18:00-02:00 shift counts for Friday. With no such shift the
     * business date is the local date of the clock-in.
     */
    public void link(TimeEntry entry) {
        ZoneId zone = entry.getEstablishment().getZoneId();
        LocalDateTime clockIn = LocalDateTime.ofInstant(entry.getClockIn(), zone);
        WorkerShift shift = workerShiftRepository.findShiftsForClockIn(entry.getWorker().getUserId(), clockIn,
                        clockIn.plus(properties.shiftMatchWindow())).stream()
                .findFirst()
                .orElse(null);
        entry.setShift(shift);
        entry.setBusinessDate(shift != null ? shift.getDate() : clockIn.toLocalDate());
    }

    /**
     * After a shift is edited, its entries that no longer fit it (it now belongs to another worker, or
     * the clock-in falls outside its new window) lose the link and keep their business date, as when a
     * shift is deleted. The entries that still fit follow the shift's date.
     */
    public void relinkAfterShiftChange(WorkerShift shift) {
        ZoneId zone = shift.getWorker().getEstablishment().getZoneId();
        Instant windowStart = shift.getStartDateTime().minus(properties.shiftMatchWindow()).atZone(zone).toInstant();
        Instant windowEnd = shift.getEndDateTime().atZone(zone).toInstant();
        timeEntryRepository.unlinkFromShift(shift.getId(), shift.getWorker().getUserId(), windowStart, windowEnd);
        timeEntryRepository.moveToBusinessDate(shift.getId(), shift.getDate());
    }
}
