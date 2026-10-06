package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.dto.request.MerchantPromoRequest;
import com.relyon.economizaai.exception.MerchantAccessNotFoundException;
import com.relyon.economizaai.exception.MerchantPromoInvalidException;
import com.relyon.economizaai.exception.MerchantPromoOverlapException;
import com.relyon.economizaai.exception.MerchantSubscriptionRequiredException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantPromoServiceTest {

    private static final String CHAIN_ROOT = "93015006";
    private static final String EAN = "7891000100103";

    @Mock private MerchantPromoRepository promoRepository;
    @Mock private MerchantAccessRepository merchantAccessRepository;
    @Mock private MerchantSubscriptionService merchantSubscriptionService;
    @Mock private ProductRepository productRepository;
    @Mock private PriceObservationRepository observationRepository;

    @InjectMocks private MerchantPromoService service;

    private User merchantUser;

    @BeforeEach
    void setUp() {
        merchantUser = User.builder().id(UUID.randomUUID()).name("Mercado Teste")
                .email("merchant@economizaai.app").role(Role.MERCHANT).build();
        lenient().when(merchantAccessRepository.findAllByUserId(merchantUser.getId()))
                .thenReturn(List.of(MerchantAccess.builder().user(merchantUser).cnpjRoot(CHAIN_ROOT).build()));
        lenient().when(promoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MerchantPromoRequest validRequest() {
        return new MerchantPromoRequest(null, EAN, "Leite 1L",
                new BigDecimal("4.991"), new BigDecimal("6.49"),
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 17));
    }

    @Test
    void create_valid_scalesPriceAndMatchesProductByEan() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Leite Integral 1L").build();
        when(productRepository.findByEan(EAN)).thenReturn(Optional.of(product));

        var response = service.create(merchantUser, validRequest(), MerchantPromoSource.MANUAL);

        assertThat(response.promoPrice()).isEqualByComparingTo("4.99");
        assertThat(response.productId()).isEqualTo(product.getId());
        assertThat(response.cnpjRoot()).isEqualTo(CHAIN_ROOT);
        verify(merchantSubscriptionService).requirePublishing(CHAIN_ROOT);
    }

    @Test
    void create_withoutActiveSubscription_blocked() {
        doThrow(new MerchantSubscriptionRequiredException())
                .when(merchantSubscriptionService).requirePublishing(CHAIN_ROOT);

        assertThrows(MerchantSubscriptionRequiredException.class,
                () -> service.create(merchantUser, validRequest(), MerchantPromoSource.MANUAL));
        verify(promoRepository, never()).save(any());
    }

    @Test
    void create_invalidEan_rejected() {
        var request = new MerchantPromoRequest(null, "12AB", null,
                new BigDecimal("4.99"), null, LocalDate.now(), LocalDate.now().plusDays(1));

        var thrown = assertThrows(MerchantPromoInvalidException.class,
                () -> service.create(merchantUser, request, MerchantPromoSource.MANUAL));
        assertThat(thrown.getMessageKey()).isEqualTo("merchant.promo.invalid.ean");
    }

    @Test
    void create_endBeforeStart_rejected() {
        var request = new MerchantPromoRequest(null, EAN, null,
                new BigDecimal("4.99"), null, LocalDate.now(), LocalDate.now().minusDays(1));

        var thrown = assertThrows(MerchantPromoInvalidException.class,
                () -> service.create(merchantUser, request, MerchantPromoSource.MANUAL));
        assertThat(thrown.getMessageKey()).isEqualTo("merchant.promo.invalid.dates");
    }

    @Test
    void create_overlappingActivePromo_rejected() {
        when(promoRepository.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(true);

        assertThrows(MerchantPromoOverlapException.class,
                () -> service.create(merchantUser, validRequest(), MerchantPromoSource.MANUAL));
    }

    @Test
    void resolveChain_explicitChainNotGranted_refused() {
        assertThrows(MerchantAccessNotFoundException.class,
                () -> service.resolveChain(merchantUser, "99999999"));
    }

    @Test
    void resolveChain_omittedWithMultipleChains_requiresExplicit() {
        when(merchantAccessRepository.findAllByUserId(merchantUser.getId())).thenReturn(List.of(
                MerchantAccess.builder().user(merchantUser).cnpjRoot(CHAIN_ROOT).build(),
                MerchantAccess.builder().user(merchantUser).cnpjRoot("11111111").build()));

        var thrown = assertThrows(MerchantPromoInvalidException.class,
                () -> service.resolveChain(merchantUser, null));
        assertThat(thrown.getMessageKey()).isEqualTo("merchant.promo.invalid.chain");
    }

    @Test
    void create_verifiedWhenReceiptConfirmsPrice() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Leite Integral 1L").build();
        when(productRepository.findByEan(EAN)).thenReturn(Optional.of(product));
        when(observationRepository.existsConfirmingObservation(any(), any(), any(), any(), any()))
                .thenReturn(true);

        var response = service.create(merchantUser, validRequest(), MerchantPromoSource.MANUAL);

        assertThat(response.verifiedByReceipts()).isTrue();
    }
}
