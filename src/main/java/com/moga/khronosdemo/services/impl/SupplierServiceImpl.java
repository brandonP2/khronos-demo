package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.SupplierRequestDTO;
import com.moga.khronosdemo.dtos.response.ErrorCode;
import com.moga.khronosdemo.dtos.response.SupplierResponseDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Supplier;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.SupplierMapper;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.PurchaseOrderRepository;
import com.moga.khronosdemo.repositories.SupplierRepository;
import com.moga.khronosdemo.services.SupplierService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * An establishment's suppliers. Names are unique within the establishment whatever their case: the
 * service checks first, and the unique index on lower(name) answers the same 409 if two requests race.
 * A supplier with purchase orders is kept with them; deleting one without clears it from its products.
 */
@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {

    private static final String UNIQUE_NAME_INDEX = "ux_suppliers_establishment_name";

    private final SupplierRepository supplierRepository;
    private final EstablishmentRepository establishmentRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierMapper supplierMapper;

    public SupplierServiceImpl(SupplierRepository supplierRepository, EstablishmentRepository establishmentRepository,
                               PurchaseOrderRepository purchaseOrderRepository, SupplierMapper supplierMapper) {
        this.supplierRepository = supplierRepository;
        this.establishmentRepository = establishmentRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierMapper = supplierMapper;
    }

    // By name, whatever its case
    @Override
    @Transactional(readOnly = true)
    public List<SupplierResponseDTO> findByEstablishment(Long establishmentId) {
        return supplierRepository.findByEstablishment(establishmentId).stream()
                .map(supplierMapper::toResponse)
                .toList();
    }

    @Override
    public SupplierResponseDTO create(SupplierRequestDTO request) {
        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", request.establishmentId()));
        checkNameFree(establishment.getId(), request.name(), null);
        Supplier supplier = new Supplier(establishment);
        supplierMapper.updateEntity(supplier, request);
        return save(supplier);
    }

    // The establishment never changes: the one in the request is ignored
    @Override
    public SupplierResponseDTO update(Long id, SupplierRequestDTO request) {
        Supplier supplier = getSupplier(id);
        checkNameFree(supplier.getEstablishment().getId(), request.name(), id);
        supplierMapper.updateEntity(supplier, request);
        return save(supplier);
    }

    // The database clears the supplier from its products (ON DELETE SET NULL)
    @Override
    public void delete(Long id) {
        Supplier supplier = getSupplier(id);
        if (purchaseOrderRepository.existsBySupplier_Id(id)) {
            throw new BusinessRuleException("Cannot delete a supplier that has purchase orders",
                    ErrorCode.SUPPLIER_HAS_ORDERS);
        }
        supplierRepository.delete(supplier);
    }

    // Checked before the entity changes, so the query doesn't flush a duplicate name first
    private void checkNameFree(Long establishmentId, String name, Long excludedId) {
        if (supplierRepository.existsByName(establishmentId, name.strip(), excludedId)) {
            throw nameTaken(name.strip());
        }
    }

    private SupplierResponseDTO save(Supplier supplier) {
        try {
            return supplierMapper.toResponse(supplierRepository.saveAndFlush(supplier));
        } catch (DataIntegrityViolationException ex) {
            // Only reachable if another request saved the same name after the check above
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains(UNIQUE_NAME_INDEX)) {
                throw nameTaken(supplier.getName());
            }
            throw ex;
        }
    }

    private Supplier getSupplier(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
    }

    private static BusinessRuleException nameTaken(String name) {
        return new BusinessRuleException("A supplier named '" + name + "' already exists at this establishment",
                ErrorCode.SUPPLIER_NAME_TAKEN);
    }
}
