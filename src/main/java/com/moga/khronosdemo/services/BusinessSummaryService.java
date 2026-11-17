package com.moga.khronosdemo.services;

import com.moga.khronosdemo.dtos.response.BusinessSummaryResponseDTO;

import java.time.LocalDate;

public interface BusinessSummaryService {

    BusinessSummaryResponseDTO summary(Long establishmentId, LocalDate from, LocalDate to);
}
