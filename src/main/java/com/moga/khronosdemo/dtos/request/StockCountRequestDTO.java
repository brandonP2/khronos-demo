package com.moga.khronosdemo.dtos.request;

import com.moga.khronosdemo.validation.CrossFieldChecked;
import com.moga.khronosdemo.validation.CrossFieldValid;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@CrossFieldValid
public record StockCountRequestDTO(
        @NotNull
        Long establishmentId,

        // The business day counted; today in the establishment's time zone when left out, and never later
        LocalDate date,

        // Kept as the reason of every movement the count records
        @Size(max = 255)
        String notes,

        // Each product once, all of the establishment and active
        @NotNull @Size(min = 1, max = MAX_ITEMS)
        List<@Valid @NotNull StockCountItemRequestDTO> items
) implements CrossFieldChecked {

    public static final int MAX_ITEMS = 500;

    @Override
    public Map<String, String> crossFieldErrors() {
        if (items == null) {
            return Map.of();
        }
        Set<Long> seen = new HashSet<>();
        boolean repeated = items.stream()
                .filter(Objects::nonNull)
                .map(StockCountItemRequestDTO::productId)
                .filter(Objects::nonNull)
                .anyMatch(productId -> !seen.add(productId));
        return repeated ? Map.of("items", "must not contain the same product twice") : Map.of();
    }
}
