package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.StockMovementRequestDTO;
import com.moga.khronosdemo.dtos.response.StockMovementResponseDTO;
import com.moga.khronosdemo.entities.Product;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.StockMovementType;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.StockMovementMapper;
import com.moga.khronosdemo.repositories.StockMovementRepository;
import com.moga.khronosdemo.repositories.UserRepository;
import com.moga.khronosdemo.services.StockMovementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Purchases, waste, consumption and adjustments entered by hand. Each one is recorded under its product's
 * row lock (see StockLedger), so its stockAfter is right even when two arrive at once. The stock may end
 * up below zero: that happens when a purchase wasn't entered, and isn't an error. There is no way to edit
 * or delete a movement; a mistake is corrected with another one.
 */
@Service
@Transactional
public class StockMovementServiceImpl implements StockMovementService {

    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;
    private final StockLedger stockLedger;
    private final StockMovementMapper stockMovementMapper;

    public StockMovementServiceImpl(StockMovementRepository stockMovementRepository, UserRepository userRepository,
                                    StockLedger stockLedger, StockMovementMapper stockMovementMapper) {
        this.stockMovementRepository = stockMovementRepository;
        this.userRepository = userRepository;
        this.stockLedger = stockLedger;
        this.stockMovementMapper = stockMovementMapper;
    }

    // Newest business day first, then the latest recorded; every filter is optional
    @Override
    @Transactional(readOnly = true)
    public List<StockMovementResponseDTO> findByEstablishment(Long establishmentId, LocalDate from, LocalDate to,
                                                              Long productId, StockMovementType type) {
        DateRange range = new DateRange(from, to);
        return stockMovementRepository.findByEstablishment(establishmentId, range.from(), range.to(), productId, type)
                .stream()
                .map(stockMovementMapper::toResponse)
                .toList();
    }

    // The request is already validated: COUNT is refused, and the quantity has the sign its type needs
    @Override
    public StockMovementResponseDTO create(StockMovementRequestDTO request, Long actorId) {
        Product product = stockLedger.lock(request.productId());
        StockLedger.checkActive(product);
        LocalDate date = stockLedger.businessDay(request.date(), product.getEstablishment(),
                "The movement date cannot be in the future");

        StockMovementType type = request.type();
        BigDecimal quantity = type.signed(request.quantity().setScale(3, RoundingMode.UNNECESSARY));
        BigDecimal unitCost = product.getUnitCost();
        if (type == StockMovementType.PURCHASE && request.unitCost() != null) {
            // A purchase's cost is the latest known one, so it becomes the product's cost too
            unitCost = request.unitCost().setScale(2, RoundingMode.UNNECESSARY);
            product.setUnitCost(unitCost);
        }

        BigDecimal stockBefore = stockLedger.stockOf(product.getId());
        return stockMovementMapper.toResponse(stockLedger.record(product, type, quantity, unitCost,
                clean(request.reason()), date, stockBefore, getUser(actorId)));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private static String clean(String text) {
        return StringUtils.hasText(text) ? text.strip() : null;
    }
}
