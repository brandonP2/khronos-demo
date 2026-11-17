package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.config.TimeTrackingProperties;
import com.moga.khronosdemo.dtos.response.HoursReportResponseDTO;
import com.moga.khronosdemo.dtos.response.HoursTotalsResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkDayResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerDaysReportResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerHoursResponseDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.TimeEntry;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.entities.WorkerShift;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.TimeEntryMapper;
import com.moga.khronosdemo.mappers.WorkerShiftMapper;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.TimeEntryRepository;
import com.moga.khronosdemo.repositories.WorkerRateRepository;
import com.moga.khronosdemo.repositories.WorkerRepository;
import com.moga.khronosdemo.repositories.WorkerShiftRepository;
import com.moga.khronosdemo.services.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Planned against worked hours and staff costs. Each report loads everything it needs in a fixed
 * number of queries (workers, shifts, entries, rates) and adds it up in memory, however many workers
 * or days the range holds.
 */
@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    static final int MAX_DAYS = 366;
    static final String CURRENCY = "EUR";

    private static final Comparator<Worker> BY_NAME = Comparator
            .comparing((Worker worker) -> nullToEmpty(worker.getUser().getFirstName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(worker -> nullToEmpty(worker.getUser().getLastName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Worker::getUserId);

    private final EstablishmentRepository establishmentRepository;
    private final WorkerRepository workerRepository;
    private final WorkerShiftRepository workerShiftRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final WorkerRateRepository workerRateRepository;
    private final WorkerShiftMapper workerShiftMapper;
    private final TimeEntryMapper timeEntryMapper;
    private final TimeTrackingProperties properties;
    private final Clock clock;

    public ReportServiceImpl(EstablishmentRepository establishmentRepository, WorkerRepository workerRepository,
                             WorkerShiftRepository workerShiftRepository, TimeEntryRepository timeEntryRepository,
                             WorkerRateRepository workerRateRepository, WorkerShiftMapper workerShiftMapper,
                             TimeEntryMapper timeEntryMapper, TimeTrackingProperties properties, Clock clock) {
        this.establishmentRepository = establishmentRepository;
        this.workerRepository = workerRepository;
        this.workerShiftRepository = workerShiftRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.workerRateRepository = workerRateRepository;
        this.workerShiftMapper = workerShiftMapper;
        this.timeEntryMapper = timeEntryMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public HoursReportResponseDTO hoursByEstablishment(Long establishmentId, LocalDate from, LocalDate to) {
        DateRange range = DateRange.bounded(from, to, MAX_DAYS);
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", establishmentId));
        ZoneId zone = establishment.getZoneId();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);

        List<Worker> workers = new ArrayList<>(workerRepository.findWithUserByEstablishment(establishmentId));
        workers.sort(BY_NAME);
        Map<Long, WorkTally> tallies = new HashMap<>();
        workers.forEach(worker -> tallies.put(worker.getUserId(), new WorkTally(zone)));
        // Shifts and entries belong to the establishment's current workers, because a worker with either
        // cannot move to another establishment; anything else is skipped rather than failing the report
        for (WorkerShift shift : workerShiftRepository.findByEstablishmentBetween(establishmentId, range.from(), range.to())) {
            WorkTally tally = tallies.get(shift.getWorker().getUserId());
            if (tally != null) {
                tally.add(shift);
            }
        }
        for (TimeEntry entry : timeEntryRepository.findCountedByEstablishment(establishmentId, range.from(), range.to())) {
            WorkTally tally = tallies.get(entry.getWorker().getUserId());
            if (tally != null) {
                tally.add(entry);
            }
        }
        Map<Long, RateSchedule> rates = RateSchedule.byWorker(workerRateRepository.findByEstablishment(establishmentId));

        List<WorkerHoursResponseDTO> rows = new ArrayList<>();
        for (Worker worker : workers) {
            WorkTally tally = tallies.get(worker.getUserId());
            // Inactive workers only appear when they have something in the range
            if (!worker.getActive() && tally.isEmpty()) {
                continue;
            }
            rows.add(row(worker, tally, rates.getOrDefault(worker.getUserId(), RateSchedule.none()), today, now));
        }
        return new HoursReportResponseDTO(range.from(), range.to(), CURRENCY, rows, totals(rows));
    }

    @Override
    public WorkerDaysReportResponseDTO daysByWorker(Long workerId, LocalDate from, LocalDate to, boolean includeCosts) {
        DateRange range = DateRange.bounded(from, to, MAX_DAYS);
        Worker worker = workerRepository.findWithUserAndEstablishmentById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker", workerId));
        ZoneId zone = worker.getEstablishment().getZoneId();
        Instant now = clock.instant();

        WorkTally whole = new WorkTally(zone);
        Map<LocalDate, WorkTally> days = new TreeMap<>();
        for (WorkerShift shift : workerShiftRepository.findByWorker(workerId, range.from(), range.to())) {
            whole.add(shift);
            days.computeIfAbsent(shift.getDate(), day -> new WorkTally(zone)).add(shift);
        }
        for (TimeEntry entry : timeEntryRepository.findCountedByWorker(workerId, range.from(), range.to())) {
            whole.add(entry);
            days.computeIfAbsent(entry.getBusinessDate(), day -> new WorkTally(zone)).add(entry);
        }
        // A worker reading their own report never gets rates or costs, so they are not even loaded
        RateSchedule rates = includeCosts
                ? RateSchedule.of(workerRateRepository.findByWorker_UserIdOrderByEffectiveFromDesc(workerId))
                : RateSchedule.none();

        List<WorkDayResponseDTO> dayRows = new ArrayList<>();
        days.forEach((date, tally) -> dayRows.add(new WorkDayResponseDTO(
                date,
                tally.plannedMinutes(),
                tally.workedMinutes(),
                tally.dayStatus(now, properties.lateThreshold()),
                tally.shifts().stream().map(workerShiftMapper::toResponse).toList(),
                tally.entries().stream().map(timeEntryMapper::toResponse).toList())));

        User user = worker.getUser();
        long planned = whole.plannedMinutes();
        long worked = whole.workedMinutes();
        return new WorkerDaysReportResponseDTO(
                worker.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                range.from(),
                range.to(),
                planned,
                worked,
                worked - planned,
                includeCosts ? rates.costOf(whole.plannedMinutesByDay()) : null,
                includeCosts ? rates.costOf(whole.workedMinutesByDay()) : null,
                dayRows);
    }

    @Override
    public byte[] hoursCsv(Long establishmentId, LocalDate from, LocalDate to) {
        return ReportCsvFormatter.hours(hoursByEstablishment(establishmentId, from, to));
    }

    // The time-tracking record: one row per entry, with times in the establishment's time zone
    @Override
    public byte[] timeEntriesCsvByEstablishment(Long establishmentId, LocalDate from, LocalDate to,
                                                boolean includeVoided) {
        DateRange range = DateRange.bounded(from, to, MAX_DAYS);
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", establishmentId));
        List<TimeEntry> entries = timeEntryRepository.findByEstablishment(establishmentId, range.from(), range.to(),
                includeVoided);
        return ReportCsvFormatter.timeEntries(entries, establishment.getZoneId());
    }

    @Override
    public byte[] timeEntriesCsvByWorker(Long workerId, LocalDate from, LocalDate to, boolean includeVoided) {
        DateRange range = DateRange.bounded(from, to, MAX_DAYS);
        Worker worker = workerRepository.findWithUserAndEstablishmentById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker", workerId));
        List<TimeEntry> entries = timeEntryRepository.findByWorker(workerId, range.from(), range.to(), includeVoided);
        return ReportCsvFormatter.timeEntries(entries, worker.getEstablishment().getZoneId());
    }

    private WorkerHoursResponseDTO row(Worker worker, WorkTally tally, RateSchedule rates, LocalDate today, Instant now) {
        User user = worker.getUser();
        long planned = tally.plannedMinutes();
        long worked = tally.workedMinutes();
        return new WorkerHoursResponseDTO(
                worker.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                worker.getActive(),
                planned,
                worked,
                worked - planned,
                tally.shifts().size(),
                tally.entries().size(),
                tally.lateCount(properties.lateThreshold()),
                tally.missedShiftsCount(now),
                tally.unplannedCount(),
                tally.openEntriesCount(),
                rates.rateOn(today),
                rates.costOf(tally.plannedMinutesByDay()),
                rates.costOf(tally.workedMinutesByDay()));
    }

    private static HoursTotalsResponseDTO totals(List<WorkerHoursResponseDTO> rows) {
        long planned = rows.stream().mapToLong(WorkerHoursResponseDTO::plannedMinutes).sum();
        long worked = rows.stream().mapToLong(WorkerHoursResponseDTO::workedMinutes).sum();
        return new HoursTotalsResponseDTO(planned, worked, worked - planned,
                sumKnown(rows.stream().map(WorkerHoursResponseDTO::plannedCost).toList()),
                sumKnown(rows.stream().map(WorkerHoursResponseDTO::actualCost).toList()));
    }

    // Adds up the known costs; null when no worker has one
    private static BigDecimal sumKnown(List<BigDecimal> costs) {
        return costs.stream().filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
