package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.response.BusinessSummaryResponseDTO;
import com.moga.khronosdemo.dtos.response.CashClosingResponseDTO;
import com.moga.khronosdemo.dtos.response.ExpenseResponseDTO;
import com.moga.khronosdemo.dtos.response.HoursReportResponseDTO;
import com.moga.khronosdemo.dtos.response.SummaryDayResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerHoursResponseDTO;
import com.moga.khronosdemo.entities.TimeEntry;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.CashClosingStatus;
import com.moga.khronosdemo.entities.enums.DayClosingStatus;
import com.moga.khronosdemo.entities.enums.ExpenseCategory;
import com.moga.khronosdemo.entities.enums.PaymentMethod;
import com.moga.khronosdemo.entities.enums.TimeEntrySource;
import com.moga.khronosdemo.entities.enums.TimeEntryStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.moga.khronosdemo.services.impl.CsvWriter.text;

/**
 * The CSV exports, with Spanish headers and values for spreadsheets set to Spanish: dates as
 * dd/MM/yyyy, times in the establishment's time zone and amounts with a decimal comma. Text that users
 * typed goes through CsvWriter.text, so it can't run as a formula.
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

    private static final String[] CASH_CLOSINGS_HEADER = {
            "Día de servicio", "Estado", "Fondo inicial (EUR)", "Ventas en efectivo (EUR)", "Ventas con tarjeta (EUR)",
            "Otras ventas (EUR)", "Ventas totales (EUR)", "Gastos en efectivo (EUR)", "Retiradas de efectivo (EUR)",
            "Efectivo esperado (EUR)", "Efectivo contado (EUR)", "Descuadre (EUR)", "Motivo del descuadre",
            "Propinas (EUR)", "Notas", "Cerrado el", "Cerrado por"
    };

    private static final String[] EXPENSES_HEADER = {
            "Fecha", "Categoría", "Descripción", "Proveedor", "Importe (EUR)", "Medio de pago", "Notas", "Registrado por"
    };

    private static final String[] SUMMARY_HEADER = {
            "Fecha", "Ventas (EUR)", "Gastos (EUR)", "Coste de personal (EUR)", "Margen (EUR)", "Estado del cierre",
            "Descuadre (EUR)"
    };

    // One person's entries together, in the order they happened
    private static final Comparator<TimeEntry> BY_PERSON_THEN_CLOCK_IN = Comparator
            .comparing((TimeEntry entry) -> Objects.toString(entry.getWorker().getUser().getFirstName(), ""),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> Objects.toString(entry.getWorker().getUser().getLastName(), ""),
                    String.CASE_INSENSITIVE_ORDER)
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

    // One row per closing, in the order given; closing times in the establishment's time zone
    static byte[] cashClosings(List<CashClosingResponseDTO> closings, ZoneId zone) {
        CsvWriter csv = new CsvWriter().row(CASH_CLOSINGS_HEADER);
        for (CashClosingResponseDTO closing : closings) {
            csv.row(
                    closing.businessDate().format(DATE),
                    closing.status() == CashClosingStatus.CLOSED ? "Cerrado" : "Borrador",
                    money(closing.openingFloat()),
                    money(closing.cashSales()),
                    money(closing.cardSales()),
                    money(closing.otherSales()),
                    money(closing.totalSales()),
                    money(closing.cashExpenses()),
                    money(closing.cashWithdrawals()),
                    money(closing.expectedCash()),
                    money(closing.countedCash()),
                    money(closing.difference()),
                    text(closing.differenceReason()),
                    money(closing.tips()),
                    text(closing.notes()),
                    dateTime(closing.closedAt(), zone),
                    text(closing.closedByName()));
        }
        return csv.toBytes();
    }

    // One row per expense, in the order given
    static byte[] expenses(List<ExpenseResponseDTO> expenses) {
        CsvWriter csv = new CsvWriter().row(EXPENSES_HEADER);
        for (ExpenseResponseDTO expense : expenses) {
            csv.row(
                    expense.date().format(DATE),
                    category(expense.category()),
                    text(expense.description()),
                    text(expense.supplier()),
                    money(expense.amount()),
                    paymentMethod(expense.paymentMethod()),
                    text(expense.notes()),
                    text(expense.createdByName()));
        }
        return csv.toBytes();
    }

    // One row per day of the summary; an unknown staff cost or a day without a closing leaves its cell empty
    static byte[] summary(BusinessSummaryResponseDTO summary) {
        CsvWriter csv = new CsvWriter().row(SUMMARY_HEADER);
        for (SummaryDayResponseDTO day : summary.days()) {
            csv.row(
                    day.date().format(DATE),
                    money(day.sales()),
                    money(day.expenses()),
                    money(day.laborCost()),
                    money(day.margin()),
                    closingStatus(day.closingStatus()),
                    money(day.cashDifference()));
        }
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

    private static String category(ExpenseCategory category) {
        return switch (category) {
            case SUPPLIES -> "Género y bebidas";
            case RENT -> "Alquiler";
            case UTILITIES -> "Suministros";
            case PAYROLL -> "Nóminas y seguros sociales";
            case MAINTENANCE -> "Mantenimiento";
            case MARKETING -> "Marketing";
            case TAXES -> "Impuestos";
            case OTHER -> "Otros";
        };
    }

    private static String paymentMethod(PaymentMethod paymentMethod) {
        return switch (paymentMethod) {
            case CASH -> "Efectivo";
            case CARD -> "Tarjeta";
            case TRANSFER -> "Transferencia";
            case OTHER -> "Otro";
        };
    }

    private static String closingStatus(DayClosingStatus status) {
        return switch (status) {
            case CLOSED -> "Cerrado";
            case DRAFT -> "Borrador";
            case MISSING -> "Falta el cierre";
            case NONE -> "Sin cierre";
        };
    }

    private static String status(TimeEntryStatus status) {
        return switch (status) {
            case OPEN -> "Abierto";
            case CLOSED -> "Cerrado";
            case VOIDED -> "Anulado";
        };
    }
}
