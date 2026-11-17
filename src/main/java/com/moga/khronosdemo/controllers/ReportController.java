package com.moga.khronosdemo.controllers;

import com.moga.khronosdemo.dtos.response.HoursReportResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerDaysReportResponseDTO;
import com.moga.khronosdemo.entities.enums.Role;
import com.moga.khronosdemo.security.SecurityUser;
import com.moga.khronosdemo.services.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Planned against worked hours and staff costs, as JSON or as CSV files to download. from and to are
 * required and cover at most 366 days.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/establishment/{establishmentId}/hours")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @access.ownsEstablishment(#establishmentId)")
    public HoursReportResponseDTO hoursByEstablishment(@PathVariable Long establishmentId,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.hoursByEstablishment(establishmentId, from, to);
    }

    // A worker may read their own report, without costs
    @GetMapping("/worker/{workerId}/days")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @access.isSelf(#workerId) or @access.ownsWorker(#workerId)")
    public WorkerDaysReportResponseDTO daysByWorker(@PathVariable Long workerId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @AuthenticationPrincipal SecurityUser principal) {
        return reportService.daysByWorker(workerId, from, to, principal.getRole() != Role.WORKER);
    }

    @GetMapping("/establishment/{establishmentId}/hours.csv")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @access.ownsEstablishment(#establishmentId)")
    public ResponseEntity<byte[]> hoursCsv(@PathVariable Long establishmentId,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv(reportService.hoursCsv(establishmentId, from, to), "horas_" + from + "_" + to + ".csv");
    }

    // Voided entries are left out unless includeVoided=true, as in the time entry listings
    @GetMapping("/establishment/{establishmentId}/time-entries.csv")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @access.ownsEstablishment(#establishmentId)")
    public ResponseEntity<byte[]> timeEntriesCsvByEstablishment(@PathVariable Long establishmentId,
                                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                @RequestParam(defaultValue = "false") boolean includeVoided) {
        return csv(reportService.timeEntriesCsvByEstablishment(establishmentId, from, to, includeVoided),
                "registro-jornada_" + from + "_" + to + ".csv");
    }

    @GetMapping("/worker/{workerId}/time-entries.csv")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @access.isSelf(#workerId) or @access.ownsWorker(#workerId)")
    public ResponseEntity<byte[]> timeEntriesCsvByWorker(@PathVariable Long workerId,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(defaultValue = "false") boolean includeVoided) {
        return csv(reportService.timeEntriesCsvByWorker(workerId, from, to, includeVoided),
                "registro-jornada_" + workerId + "_" + from + "_" + to + ".csv");
    }

    private static ResponseEntity<byte[]> csv(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
