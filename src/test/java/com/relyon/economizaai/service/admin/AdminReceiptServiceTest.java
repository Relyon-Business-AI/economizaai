package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReceiptServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationAuditRepository observationAuditRepository;
    @Mock private PriceObservationRepository observationRepository;
    @Mock private LocalizedMessageService localizedMessageService;

    @InjectMocks private AdminReceiptService service;

    private Receipt receipt(ReceiptStatus status) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .chaveAcesso("43260412345678000190650010000123451123456780")
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190")
                .marketName("Mercado X")
                .marketAddress("Rua Y, 123")
                .issuedAt(LocalDateTime.now())
                .totalAmount(new BigDecimal("57.80"))
                .status(status)
                .items(new ArrayList<>())
                .build();
        receipt.setCreatedAt(LocalDateTime.now());
        return receipt;
    }

    @Test
    void list_unsortedPageable_defaultsToIssuedAtDescAndMapsSummaries() {
        var receipt = receipt(ReceiptStatus.CONFIRMED);
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(receipt)));

        var page = service.list(null, null, null, null, null, null, null, null, null, false, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("Mercado X", page.getContent().get(0).marketName());

        verify(receiptRepository)
                .findAll(any(Specification.class), sortedPageableCaptor.capture());
        var appliedSort = sortedPageableCaptor.getValue().getSort().getOrderFor("issuedAt");
        assertTrue(appliedSort != null && appliedSort.getDirection() == Sort.Direction.DESC);
    }

    @Test
    void list_preservesCallerSortWhenAlreadySorted() {
        var requested = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "totalAmount"));
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(LocalDateTime.now().minusDays(7), LocalDateTime.now(),
                "  12345678000190  ", List.of(ProductCategory.GROCERIES), "  arroz  ", UUID.randomUUID(), null, null, null, false, requested);

        verify(receiptRepository)
                .findAll(any(Specification.class), sortedPageableCaptor.capture());
        assertEquals(requested, sortedPageableCaptor.getValue());
    }

    @Test
    void list_blankCnpjAndSearchAreTreatedAsNull() {
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.list(null, null, "   ", List.of(), "   ", null, null, null, null, false, PageRequest.of(0, 20));

        assertEquals(0, page.getTotalElements());
    }

    @Test
    void get_returnsReceiptWithOwner() {
        var receipt = receipt(ReceiptStatus.PENDING_CONFIRMATION);
        var owner = User.builder().id(UUID.randomUUID()).name("Robson").email("robson@economizaai.app").build();
        receipt.setUser(owner);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        var response = service.get(receipt.getId());

        assertEquals(receipt.getId(), response.receipt().id());
        assertEquals("Mercado X", response.receipt().marketName());
        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.receipt().status());
        assertEquals(owner.getId(), response.owner().id());
        assertEquals("robson@economizaai.app", response.owner().email());
    }

    @Test
    void get_localizesFailureMessageForFailedParse() {
        var receipt = receipt(ReceiptStatus.FAILED_PARSE);
        receipt.setParseErrorReason("infosimples.error");
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(localizedMessageService.translate("infosimples.error", "")).thenReturn("Falha ao consultar a nota.");

        var response = service.get(receipt.getId());

        assertEquals("Falha ao consultar a nota.", response.receipt().parseErrorMessage());
    }

    @Test
    void get_throwsWhenReceiptMissing() {
        var unknownId = UUID.randomUUID();
        when(receiptRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> service.get(unknownId));
    }

    @Test
    void purgeObservationsForReceipt_deletesAuditsThenObservationsAndReturnsCount() {
        var receiptId = UUID.randomUUID();
        var firstObservationId = UUID.randomUUID();
        var secondObservationId = UUID.randomUUID();
        var firstAudit = mock(PriceObservationAudit.class);
        var secondAudit = mock(PriceObservationAudit.class);
        var firstObservation = mock(PriceObservation.class);
        var secondObservation = mock(PriceObservation.class);
        when(firstObservation.getId()).thenReturn(firstObservationId);
        when(secondObservation.getId()).thenReturn(secondObservationId);
        when(firstAudit.getObservation()).thenReturn(firstObservation);
        when(secondAudit.getObservation()).thenReturn(secondObservation);
        var audits = List.of(firstAudit, secondAudit);
        when(observationAuditRepository.findByReceiptId(receiptId)).thenReturn(audits);

        var removed = service.purgeObservationsForReceipt(receiptId);

        assertEquals(2, removed);
        verify(observationAuditRepository).deleteAll(audits);
        verify(observationRepository).deleteAllById(List.of(firstObservationId, secondObservationId));
    }

    @Test
    void purgeObservationsForReceipt_noAudits_returnsZeroAndDeletesNothing() {
        var receiptId = UUID.randomUUID();
        when(observationAuditRepository.findByReceiptId(receiptId)).thenReturn(List.of());

        var removed = service.purgeObservationsForReceipt(receiptId);

        assertEquals(0, removed);
        verify(observationAuditRepository, never()).deleteAll(any());
        verify(observationRepository, never()).deleteAllById(any());
    }

    @Test
    void countOrphanedObservations_delegatesToRepository() {
        when(observationRepository.countOrphaned()).thenReturn(4L);

        assertEquals(4L, service.countOrphanedObservations());
    }

    @Test
    void deleteOrphanedObservations_delegatesAndReturnsCount() {
        when(observationRepository.deleteOrphaned()).thenReturn(607);

        assertEquals(607, service.deleteOrphanedObservations());
        verify(observationRepository).deleteOrphaned();
    }
}
