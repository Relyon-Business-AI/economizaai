package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.CuratedOfferRequest;
import com.relyon.economizaai.exception.EcommerceOfferNotFoundException;
import com.relyon.economizaai.model.EcommerceOffer;
import com.relyon.economizaai.repository.EcommerceOfferRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminEcommerceServiceTest {

    @Mock private EcommerceOfferRepository offerRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private AdminEcommerceService service;

    private CuratedOfferRequest request() {
        return new CuratedOfferRequest("7891234567890", null, "mercadolivre", "Café Pilão 500g",
                "https://ml/x", "https://ml/x?aff", "https://img", new BigDecimal("15.90"),
                new BigDecimal("6.00"), null, null, null);
    }

    @Test
    void createPersistsCuratedOfferWithDefaultsAndAuditEmail() {
        when(offerRepository.save(any(EcommerceOffer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(request(), "admin@economizaai.app");

        var captor = ArgumentCaptor.forClass(EcommerceOffer.class);
        verify(offerRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getEan()).isEqualTo("7891234567890");
        assertThat(saved.getCurrency()).isEqualTo("BRL");    // defaulted
        assertThat(saved.isActive()).isTrue();                // defaulted
        assertThat(saved.isInStock()).isTrue();               // defaulted
        assertThat(saved.isCurated()).isTrue();
        assertThat(saved.getCuratedBy()).isEqualTo("admin@economizaai.app");
        assertThat(response.provider()).isEqualTo("mercadolivre");
    }

    @Test
    void updateThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(offerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, request(), "admin@economizaai.app"))
                .isInstanceOf(EcommerceOfferNotFoundException.class);
    }

    @Test
    void deleteThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(offerRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(EcommerceOfferNotFoundException.class);
    }

    @Test
    void deleteRemovesWhenPresent() {
        var id = UUID.randomUUID();
        when(offerRepository.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(offerRepository).deleteById(id);
    }
}
