package com.moga.khronosdemo.services;

import com.moga.khronosdemo.dtos.response.HoursReportResponseDTO;
import com.moga.khronosdemo.dtos.response.WorkerDaysReportResponseDTO;

import java.time.LocalDate;

public interface ReportService {

    HoursReportResponseDTO hoursByEstablishment(Long establishmentId, LocalDate from, LocalDate to);

    WorkerDaysReportResponseDTO daysByWorker(Long workerId, LocalDate from, LocalDate to, boolean includeCosts);
}
