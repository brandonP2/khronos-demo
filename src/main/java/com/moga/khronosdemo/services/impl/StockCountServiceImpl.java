package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.StockCountItemRequestDTO;
import com.moga.khronosdemo.dtos.request.StockCountRequestDTO;
import com.moga.khronosdemo.dtos.response.ErrorCode;
import com.moga.khronosdemo.dtos.response.StockMovementResponseDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Product;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.StockMovementType;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.StockMovementMapper;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.UserRepository;
import com.moga.khronosdemo.services.StockCountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A physical stock count. For each product counted it records a COUNT movement with the difference
 * between what was counted and the stock at that moment, also when they match (a 0 leaves on record that
 * the product was counted), so the stock becomes what was counted. The whole count is one transaction
 * under the products' row locks, taken in id order (see StockLedger): a movement sent meanwhile either
 * lands before the count, which then counts against it, or waits for the count to finish.
 */
@Service
@Transactional
public class StockCountServiceImpl implements StockCountService {

    private final EstablishmentRepository establishmentRepository;
    private final UserRepository userRepository;
    private final StockLedger stockLedger;
    private final StockMovementMapper stockMovementMapper;

    public StockCountServiceImpl(EstablishmentRepository establishmentRepository, UserRepository userRepository,
                                 StockLedger stockLedger, StockMovementMapper stockMovementMapper) {
        this.establishmentRepository = establishmentRepository;
        this.userRepository = userRepository;
        this.stockLedger = stockLedger;
        this.stockMovementMapper = stockMovementMapper;
    }

    // The request is already validated: 1 to 500 items, each product once. All or nothing
    @Override
    public List<StockMovementResponseDTO> count(StockCountRequestDTO request, Long actorId) {
        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Establishment", request.establishmentId()));
        LocalDate date = stockLedger.businessDay(request.date(), establishment,
                "The count date cannot be in the future");

        List<Long> productIds = request.items().stream().map(StockCountItemRequestDTO::productId).toList();
        Map<Long, Product> products = stockLedger.lock(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Product product : products.values()) {
            if (!Objects.equals(product.getEstablishment().getId(), establishment.getId())) {
                throw new BusinessRuleException("Product '" + product.getName() + "' belongs to another establishment",
                        ErrorCode.OTHER_ESTABLISHMENT);
            }
            StockLedger.checkActive(product);
        }

        Map<Long, BigDecimal> stock = stockLedger.stockOf(productIds);
        User author = getUser(actorId);
        String reason = StringUtils.hasText(request.notes()) ? request.notes().strip() : null;
        List<StockMovementResponseDTO> recorded = new ArrayList<>();
        for (StockCountItemRequestDTO item : request.items()) {
            Product product = products.get(item.productId());
            BigDecimal before = stock.get(item.productId());
            BigDecimal difference = item.countedQuantity().setScale(3, RoundingMode.UNNECESSARY).subtract(before);
            recorded.add(stockMovementMapper.toResponse(stockLedger.record(product, StockMovementType.COUNT,
                    difference, product.getUnitCost(), reason, date, before, author)));
        }
        return recorded;
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
