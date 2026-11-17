package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.response.ErrorCode;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Product;
import com.moga.khronosdemo.entities.PurchaseOrder;
import com.moga.khronosdemo.entities.StockMovement;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.StockMovementType;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.repositories.ProductRepository;
import com.moga.khronosdemo.repositories.StockMovementRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The stock ledger. A product's stock is not stored anywhere: it is the sum of its movements, added up
 * in the database (one grouped query for any number of products). Movements are only ever added.
 * <p>
 * Every movement is recorded while its product's row is locked (ProductRepository.lockAllById), and its
 * stock_after is the stock read under that lock plus its quantity. Two movements of the same product
 * therefore take turns, and the second one starts from the stock the first one left. Whatever changes a
 * product (its unit, whether it is active, deleting it) takes the same lock, so a movement never slips in
 * between that check and the change.
 * <p>
 * Lock order. Every request takes the row locks it needs in this one order, leaving out those it doesn't
 * need, so no two requests can each hold a lock the other is waiting for:
 * <ol>
 *   <li>the establishment (EstablishmentRepository.lockById): cash closings, expenses, and receiving a
 *   purchase order, which may create an expense;</li>
 *   <li>a purchase order (PurchaseOrderRepository.lockById): editing, ordering, cancelling, deleting or
 *   receiving it;</li>
 *   <li>products, in id order and in one query (lock below): every stock movement and count, changes to a
 *   product, and the products of an order being edited, ordered or received.</li>
 * </ol>
 * Receiving an order therefore goes establishment, order, products, then creates its expense under the
 * establishment lock it already holds. Deleting an expense locks the establishment and then, through the
 * foreign key that clears purchase_orders.expense_id, the order row: the same order.
 */
@Component
class StockLedger {

    private static final BigDecimal NO_STOCK = BigDecimal.ZERO.setScale(3);

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final Clock clock;

    StockLedger(ProductRepository productRepository, StockMovementRepository stockMovementRepository, Clock clock) {
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.clock = clock;
    }

    /**
     * Locks the products' rows until the transaction ends and returns them in id order. Call it before
     * anything else reads these products in the transaction, so their fields are the ones under the lock.
     *
     * @throws ResourceNotFoundException for the first product that doesn't exist
     */
    List<Product> lock(Collection<Long> productIds) {
        List<Long> ids = productIds.stream().distinct().sorted().toList();
        List<Product> products = productRepository.lockAllById(ids);
        if (products.size() != ids.size()) {
            Set<Long> found = products.stream().map(Product::getId).collect(Collectors.toSet());
            Long missing = ids.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new ResourceNotFoundException("Product", missing);
        }
        return products;
    }

    Product lock(Long productId) {
        return lock(List.of(productId)).get(0);
    }

    // Each product's stock now, 0 for those without movements
    Map<Long, BigDecimal> stockOf(Collection<Long> productIds) {
        Map<Long, BigDecimal> stock = new HashMap<>();
        if (productIds.isEmpty()) {
            return stock;
        }
        productIds.forEach(id -> stock.put(id, NO_STOCK));
        stockMovementRepository.sumByProducts(productIds)
                .forEach(sum -> stock.put(sum.getProductId(), sum.getStock()));
        return stock;
    }

    BigDecimal stockOf(Long productId) {
        return stockOf(List.of(productId)).get(productId);
    }

    // The stock of every product of the establishment; a product missing from the map has none
    Map<Long, BigDecimal> stockOfEstablishment(Long establishmentId) {
        return stockMovementRepository.sumByEstablishment(establishmentId).stream()
                .collect(Collectors.toMap(StockMovementRepository.ProductStock::getProductId,
                        StockMovementRepository.ProductStock::getStock));
    }

    static BigDecimal stockIn(Map<Long, BigDecimal> stock, Long productId) {
        return stock.getOrDefault(productId, NO_STOCK);
    }

    /**
     * Records a movement of a product this transaction has locked.
     *
     * @param quantity    signed, with three decimals
     * @param stockBefore the product's stock read under the lock, before this movement
     */
    StockMovement record(Product product, StockMovementType type, BigDecimal quantity, BigDecimal unitCost,
                         String reason, LocalDate date, BigDecimal stockBefore, User author) {
        return record(product, type, quantity, unitCost, reason, date, stockBefore, null, author);
    }

    // The same, for a purchase received with a purchase order
    StockMovement record(Product product, StockMovementType type, BigDecimal quantity, BigDecimal unitCost,
                         String reason, LocalDate date, BigDecimal stockBefore, PurchaseOrder purchaseOrder,
                         User author) {
        return stockMovementRepository.save(new StockMovement(product, type, quantity, unitCost, reason, date,
                stockBefore.add(quantity), purchaseOrder, author, auditNow()));
    }

    // An inactive product takes no new movements or purchase order lines
    static void checkActive(Product product) {
        if (!product.isActive()) {
            throw new BusinessRuleException("Product '" + product.getName() + "' is inactive; activate it first",
                    ErrorCode.PRODUCT_INACTIVE);
        }
    }

    // The business day asked for, or today when left out. "Today" is the establishment's local date, and
    // a later day answers 409 with the message given
    LocalDate businessDay(LocalDate requested, Establishment establishment, String laterThanTodayMessage) {
        LocalDate today = today(establishment);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new BusinessRuleException(laterThanTodayMessage);
        }
        return requested;
    }

    LocalDate today(Establishment establishment) {
        return LocalDate.ofInstant(clock.instant(), establishment.getZoneId());
    }

    // Audit times keep the precision PostgreSQL stores, so a response matches what is read back later
    Instant auditNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
