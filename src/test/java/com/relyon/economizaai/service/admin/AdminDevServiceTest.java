package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDevServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private AdminDevService adminDevService;

    private User user(String email) {
        return User.builder().email(email).household(new Household()).build();
    }

    @Test
    void seedDiscountedReceipt_buildsPendingReceiptWithDiscountAndPromotionalItems() {
        var caller = user("dev@economizaai.app");
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = adminDevService.seedDiscountedReceipt(caller, null);

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.status());
        assertEquals(0, response.discountTotal().compareTo(new BigDecimal("5.00")));
        assertEquals(4, response.items().size());

        var promoLines = response.items().stream().filter(item -> item.promotional()).toList();
        assertEquals(3, promoLines.size(), "three lines should be promotional");
        promoLines.forEach(promoLine ->
                assertTrue(promoLine.paidTotalPrice().compareTo(promoLine.totalPrice()) < 0));
    }

    @Test
    void seedDiscountedReceipt_seedsIntoTargetAccountWhenTargetEmailGiven() {
        var caller = user("dev@economizaai.app");
        var target = user("contato@economizaai.app");
        when(userRepository.findByEmail("contato@economizaai.app")).thenReturn(Optional.of(target));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adminDevService.seedDiscountedReceipt(caller, " contato@economizaai.app ");

        var saved = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(saved.capture());
        assertEquals(target, saved.getValue().getUser());
        assertEquals(target.getHousehold(), saved.getValue().getHousehold());
    }

    @Test
    void seedDiscountedReceipt_replacesEarlierSeededReceipts() {
        var caller = user("dev@economizaai.app");
        var earlierSeed = Receipt.builder()
                .user(caller).household(caller.getHousehold())
                .qrPayload(AdminDevService.SEED_QR_PAYLOAD).build();
        when(receiptRepository.findAllByHouseholdIdAndQrPayload(
                caller.getHousehold().getId(), AdminDevService.SEED_QR_PAYLOAD))
                .thenReturn(List.of(earlierSeed));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adminDevService.seedDiscountedReceipt(caller, null);

        verify(receiptRepository).deleteAll(List.of(earlierSeed));
    }

    @Test
    void seedDiscountedReceipt_throwsWhenTargetEmailUnknown() {
        var caller = user("dev@economizaai.app");
        when(userRepository.findByEmail("ghost@economizaai.app")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> adminDevService.seedDiscountedReceipt(caller, "ghost@economizaai.app"));
    }
}
