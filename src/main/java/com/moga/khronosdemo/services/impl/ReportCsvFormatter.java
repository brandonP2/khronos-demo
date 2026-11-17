package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.response.HoursReportResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerHoursResponseDTO;
import com.moga.khronosdemo.entities.TimeEntry;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.TimeEntrySource;
import com.moga.khronosdemo.entities.enums.TimeEntryStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import static com.moga.khronosdemo.services.impl.CsvWriter.text;

/**
 * The CSV exports, with Spanish headers and values for spreadsheets set to Spanish: dates as
 * dd/MM/yyyy, times in the establishment's time zone and amounts with a decimal comma.
 */
final class ReportCsvFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final String[] HOURS_HEADER = {
            "ID trabajador", "Nombre", "Apellidos", "Activo", "Minutos planificados", "Minutos trabajados",
            "Diferencia (min)", "Turnos", "Fichajes", "Retrasos", "Turnos sin fichar", "Fichajes sin turno",
            "Fichajes abiertos", "Tarifa actual (EUR/h)", "Coste planificado (EUR)", "Coste real (EUR)"
    };

    private static final String[] TIME_ENTRIES_HEADER = {
            "Nombre", "Apellidos", "Día de servicio", "Entrada", "Salida", "Pausa (min)", "Horas trabajadas",
            "Origen", "Corregido", "Estado"
    };

    // One person's entries together, in the order they happened
    private static final Comparator<TimeEntry> BY_PERSON_THEN_CLOCK_IN = Comparator
            .comparing((TimeEntry entry) -> nullToEmpty(entry.getWorker().getUser().getFirstName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> nullToEmpty(entry.getWorker().getUser().getLastName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.getWorker().getUserId())
            .thenComparing(TimeEntry::getClockIn)
            .thenComparing(TimeEntry::getId);

    private ReportCsvFormatter() {
    }

    static byte[] hours(HoursReportResponseDTO report) {
        CsvWriter csv = new CsvWriter().row(HOURS_HEADER);
        for (WorkerHoursResponseDTO worker : report.workers()) {
            csv.row(
                    String.valueOf(worker.workerId()),
                    text(worker.firstName()),
                    text(worker.lastName()),
                    yesNo(worker.isActive()),
                    String.valueOf(worker.plannedMinutes()),
                    String.valueOf(worker.workedMinutes()),
                    String.valueOf(worker.differenceMinutes()),
                    String.valueOf(worker.shiftsCount()),
                    String.valueOf(worker.entriesCount()),
                    String.valueOf(worker.lateCount()),
                    String.valueOf(worker.missedShiftsCount()),
                    String.valueOf(worker.unplannedCount()),
                    String.valueOf(worker.openEntriesCount()),
                    money(worker.currentHourlyRate()),
                    money(worker.plannedCost()),
                    money(worker.actualCost()));
        }
        return csv.toBytes();
    }

    static byte[] timeEntries(List<TimeEntry> entries, ZoneId zone) {
        CsvWriter csv = new CsvWriter().row(TIME_ENTRIES_HEADER);
        entries.stream().sorted(BY_PERSON_THEN_CLOCK_IN).forEach(entry -> {
            User user = entry.getWorker().getUser();
            csv.row(
                    text(user.getFirstName()),
                    text(user.getLastName()),
                    entry.getBusinessDate().format(DATE),
                    dateTime(entry.getClockIn(), zone),
                    dateTime(entry.getClockOut(), zone),
                    String.valueOf(entry.getBreakMinutes()),
                    hoursAndMinutes(entry.getWorkedMinutes()),
                    entry.getSource() == TimeEntrySource.WORKER ? "Trabajador" : "Responsable",
                    yesNo(entry.isEdited()),
                    status(entry.getStatus()));
        });
        return csv.toBytes();
    }

    private static String dateTime(Instant instant, ZoneId zone) {
        return instant != null ? instant.atZone(zone).format(DATE_TIME) : "";
    }

    // 462 minutes are "7:42"; empty while the entry is open
    private static String hoursAndMinutes(Integer minutes) {
        return minutes != null ? "%d:%02d".formatted(minutes / 60, minutes % 60) : "";
    }

    private static String money(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') : "";
    }

    private static String yesNo(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Sí" : "No";
    }

    private static String status(TimeEntryStatus status) {
        return switch (status) {
            case OPEN -> "Abierto";
            case CLOSED -> "Cerrado";
            case VOIDED -> "Anulado";
        };
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
