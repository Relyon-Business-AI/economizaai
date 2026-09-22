package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.EcommerceOfferResponse;
import com.relyon.economizai.exception.ReceiptItemNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.service.ecommerce.EcommerceOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * User-facing e-commerce comparison: "vale a pena comprar online?" for a scanned item.
 * Returns the best online offer (curated + any configured provider) compared to what
 * the user paid. 204 when there's no offer for that item's EAN.
 */
@Tag(name = "E-commerce")
@RestController
@RequestMapping("/api/v1/receipt-items")
@RequiredArgsConstructor
public class EcommerceController {

    private final ReceiptItemRepository receiptItemRepository;
    private final EcommerceOfferService ecommerceOfferService;

    @Operation(summary = "Best online offer for a scanned item",
            description = "Compares the cheapest online offer (curated + configured providers) to what the user "
                    + "paid on this item. 204 when no offer matches the item's EAN. Optional cep for freight.")
    @GetMapping("/{id}/offer")
    public ResponseEntity<EcommerceOfferResponse> offerForItem(@AuthenticationPrincipal User user,
                                                               @PathVariable UUID id,
                                                               @RequestParam(required = false) String cep) {
        var item = receiptItemRepository.findById(id)
                .orElseThrow(() -> new ReceiptItemNotFoundException());
        // Household scoping: don't reveal items outside the caller's household.
        if (item.getReceipt() == null
                || item.getReceipt().getHousehold() == null
                || !item.getReceipt().getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ReceiptItemNotFoundException();
        }
        return ecommerceOfferService.bestOfferForItem(item, cep)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
