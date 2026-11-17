package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.ExpenseRequestDTO;
import com.moga.khronosdemo.dtos.response.ExpenseResponseDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Expense;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.ExpenseCategory;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.ExpenseMapper;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.ExpenseRepository;
import com.moga.khronosdemo.repositories.UserRepository;
import com.moga.khronosdemo.services.ExpenseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional
public class ExpenseServiceImpl implements ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final EstablishmentRepository establishmentRepository;
    private final UserRepository userRepository;
    private final ExpenseMapper expenseMapper;
    private final Clock clock;

    public ExpenseServiceImpl(ExpenseRepository expenseRepository, EstablishmentRepository establishmentRepository,
                              UserRepository userRepository, ExpenseMapper expenseMapper, Clock clock) {
        this.expenseRepository = expenseRepository;
        this.establishmentRepository = establishmentRepository;
        this.userRepository = userRepository;
        this.expenseMapper = expenseMapper;
        this.clock = clock;
    }

    // Newest first, then by id, newest first too
    @Override
    @Transactional(readOnly = true)
    public List<ExpenseResponseDTO> findByEstablishment(Long establishmentId, LocalDate from, LocalDate to,
                                                       ExpenseCategory category) {
        DateRange range = new DateRange(from, to);
        return expenseRepository.findByEstablishment(establishmentId, range.from(), range.to(), category).stream()
                .map(expenseMapper::toResponse)
                .toList();
    }

    @Override
    public ExpenseResponseDTO create(ExpenseRequestDTO request, Long actorId) {
        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", request.establishmentId()));
        checkNotInFuture(establishment, request.date());

        User author = getUser(actorId);
        Expense expense = new Expense(establishment, author, author.displayName(), auditNow());
        expenseMapper.updateEntity(expense, request);
        return expenseMapper.toResponse(expenseRepository.save(expense));
    }

    // The establishment never changes: the one in the request is ignored
    @Override
    public ExpenseResponseDTO update(Long id, ExpenseRequestDTO request) {
        Expense expense = getExpense(id);
        checkNotInFuture(expense.getEstablishment(), request.date());
        expenseMapper.updateEntity(expense, request);
        return expenseMapper.toResponse(expense);
    }

    @Override
    public void delete(Long id) {
        expenseRepository.delete(getExpense(id));
    }

    // "Today" is the establishment's local date, so a venue in the Canaries can't enter tomorrow's expenses
    private void checkNotInFuture(Establishment establishment, LocalDate date) {
        if (date.isAfter(LocalDate.ofInstant(clock.instant(), establishment.getZoneId()))) {
            throw new BusinessRuleException("The expense date cannot be in the future");
        }
    }

    private Expense getExpense(Long id) {
        return expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense", id));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    // Audit times keep the precision PostgreSQL stores, so a response matches what is read back later
    private Instant auditNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
