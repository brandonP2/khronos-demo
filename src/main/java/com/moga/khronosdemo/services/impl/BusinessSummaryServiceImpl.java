package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.response.BusinessSummaryResponseDTO;
import com.moga.khronosdemo.entities.CashClosing;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Expense;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.repositories.CashClosingRepository;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.ExpenseRepository;
import com.moga.khronosdemo.services.BusinessSummaryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Sales, expenses, staff cost and margin of an establishment over a range of business days, and the same
 * figures for the period of equal length right before it. Both periods are loaded together in a fixed
 * number of queries (the establishment, its closings, its expenses and the four of StaffWorkLoader),
 * however long the range, and added up in memory by BusinessPeriod.
 */
@Service
@Transactional(readOnly = true)
public class BusinessSummaryServiceImpl implements BusinessSummaryService {

    private final EstablishmentRepository establishmentRepository;
    private final CashClosingRepository cashClosingRepository;
    private final ExpenseRepository expenseRepository;
    private final StaffWorkLoader staffWorkLoader;
    private final Clock clock;

    public BusinessSummaryServiceImpl(EstablishmentRepository establishmentRepository,
                                      CashClosingRepository cashClosingRepository, ExpenseRepository expenseRepository,
                                      StaffWorkLoader staffWorkLoader, Clock clock) {
        this.establishmentRepository = establishmentRepository;
        this.cashClosingRepository = cashClosingRepository;
        this.expenseRepository = expenseRepository;
        this.staffWorkLoader = staffWorkLoader;
        this.clock = clock;
    }

    @Override
    public BusinessSummaryResponseDTO summary(Long establishmentId, LocalDate from, LocalDate to) {
        DateRange range = DateRange.bounded(from, to, ReportServiceImpl.MAX_DAYS);
        Establishment establishment = getEstablishment(establishmentId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), establishment.getZoneId());
        long length = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        LocalDate previousFrom = range.from().minusDays(length);
        LocalDate previousTo = range.from().minusDays(1);

        List<CashClosing> closings = cashClosingRepository.findByEstablishmentBetween(establishmentId, previousFrom, range.to());
        List<Expense> expenses = expenseRepository.findByEstablishmentBetween(establishmentId, previousFrom, range.to());
        StaffWork staff = staffWorkLoader.load(establishment, previousFrom, range.to());

        BusinessPeriod current = new BusinessPeriod(range.from(), range.to(), closings, expenses,
                staff.between(range.from(), range.to()), today);
        BusinessPeriod previous = new BusinessPeriod(previousFrom, previousTo, closings, expenses,
                staff.between(previousFrom, previousTo), today);
        return new BusinessSummaryResponseDTO(range.from(), range.to(), ReportServiceImpl.CURRENCY, current.totals(),
                current.days(), current.expensesByCategory(), previous.asPrevious());
    }

    private Establishment getEstablishment(Long id) {
        return establishmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", id));
    }
}
