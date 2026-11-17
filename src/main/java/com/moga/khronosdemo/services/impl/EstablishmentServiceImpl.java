package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.EstablishmentRequestDTO;
import com.moga.khronosdemo.dtos.response.EstablishmentResponseDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.enums.Role;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.EstablishmentMapper;
import com.moga.khronosdemo.repositories.CashClosingRepository;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.ExpenseRepository;
import com.moga.khronosdemo.repositories.TimeEntryRepository;
import com.moga.khronosdemo.repositories.UserRepository;
import com.moga.khronosdemo.services.EstablishmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class EstablishmentServiceImpl implements EstablishmentService {

    private final EstablishmentRepository establishmentRepository;
    private final UserRepository userRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final CashClosingRepository cashClosingRepository;
    private final ExpenseRepository expenseRepository;
    private final EstablishmentMapper establishmentMapper;

    public EstablishmentServiceImpl(EstablishmentRepository establishmentRepository, UserRepository userRepository,
                                    TimeEntryRepository timeEntryRepository, CashClosingRepository cashClosingRepository,
                                    ExpenseRepository expenseRepository, EstablishmentMapper establishmentMapper) {
        this.establishmentRepository = establishmentRepository;
        this.userRepository = userRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.cashClosingRepository = cashClosingRepository;
        this.expenseRepository = expenseRepository;
        this.establishmentMapper = establishmentMapper;
    }

    @Override
    public EstablishmentResponseDTO create(EstablishmentRequestDTO request) {
        if (establishmentRepository.existsByEstablishmentAddress(request.establishmentAddress())) {
            throw new BusinessRuleException("An establishment already exists at that address");
        }
        Establishment establishment = establishmentMapper.toEntity(request, getOwner(request.ownerId()));
        return establishmentMapper.toResponse(establishmentRepository.save(establishment));
    }

    @Override
    @Transactional(readOnly = true)
    public EstablishmentResponseDTO findById(Long id) {
        return establishmentMapper.toResponse(getEstablishment(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EstablishmentResponseDTO> findAll() {
        return establishmentRepository.findAll().stream().map(establishmentMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EstablishmentResponseDTO> findByOwner(Long ownerId) {
        return establishmentRepository.findByOwner_Id(ownerId).stream().map(establishmentMapper::toResponse).toList();
    }

    @Override
    public EstablishmentResponseDTO update(Long id, EstablishmentRequestDTO request) {

        Establishment establishment = getEstablishment(id);

        if (establishmentRepository.existsByEstablishmentAddressAndIdNot(request.establishmentAddress(), id)) {
            throw new BusinessRuleException("An establishment already exists at that address");

        }
        establishmentMapper.updateEntity(establishment, request, getOwner(request.ownerId()));

        return establishmentMapper.toResponse(establishment);

    }

    // Deleting an establishment deletes its workers, so it is not allowed while it has time entries. Its cash
    // closings (drafts too) and expenses are its financial record, so they keep it from being deleted as well
    @Override
    public void delete(Long id) {
        Establishment establishment = getEstablishment(id);
        if (timeEntryRepository.existsByEstablishment_Id(id)) {
            throw new BusinessRuleException("Cannot delete an establishment that has time entries;"
                    + " deactivate its workers instead to keep the time entries");
        }
        if (cashClosingRepository.existsByEstablishment_Id(id) || expenseRepository.existsByEstablishment_Id(id)) {
            throw new BusinessRuleException("Cannot delete an establishment that has cash closings or expenses;"
                    + " keep it to keep its financial records");
        }
        establishmentRepository.delete(establishment);
    }

    private Establishment getEstablishment(Long id) {
        return establishmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", id));
    }

    private User getOwner(Long ownerId) {

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", ownerId));

        if (owner.getRole() != Role.OWNER) {
            throw new BusinessRuleException("The owner of an establishment must have the OWNER role");

        }

        return owner;

    }
}
