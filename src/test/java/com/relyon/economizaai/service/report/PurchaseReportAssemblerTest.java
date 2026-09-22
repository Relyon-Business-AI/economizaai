package com.relyon.economizaai.service.report;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseReportAssemblerTest {

    @Mock private ReceiptRepository receiptRepository;

    @InjectMocks private PurchaseReportAssembler assembler;

    private User user;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("alexandre+report@economizaai.app")
                .household(household).build();
    }

    private Receipt receipt(String cnpj, String market, LocalDateTime issuedAt, String total,
                            ReceiptItem... items) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID()).cnpjEmitente(cnpj).marketName(market)
                .chaveAcesso("52260739346861022483650190000191021190388509")
                .issuedAt(issuedAt).totalAmount(new BigDecimal(total))
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private ReceiptItem item(String description, String total, ProductCategory category, boolean excluded) {
        var receiptItem = ReceiptItem.builder()
                .lineNumber(1).rawDescription(description)
                .quantity(BigDecimal.ONE).unit("UN")
                .unitPrice(new BigDecimal(total)).totalPrice(new BigDecimal(total))
                .categoryAtConfirmation(category)
                .build();
        receiptItem.setExcluded(excluded);
        return receiptItem;
    }

    @Test
    void assemble_buildsKpisSeriesAndBreakdowns() {
        var june = receipt("11111111000111", "Zaffari", LocalDateTime.of(2026, Month.JUNE, 10, 10, 0), "30.00",
                item("ARROZ", "20.00", ProductCategory.GROCERIES, false),
                item("CERVEJA", "10.00", ProductCategory.BEVERAGES, false));
        var july = receipt("22222222000122", "Bistek", LocalDateTime.of(2026, Month.JULY, 5, 18, 0), "50.00",
                item("PICANHA", "50.00", ProductCategory.MEAT_DAIRY, false));
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(june, july));

        var report = assembler.assemble(user, null, null);

        assertThat(report.kpis().totalSpent()).isEqualByComparingTo("80.00");
        assertThat(report.kpis().receiptCount()).isEqualTo(2);
        assertThat(report.kpis().itemCount()).isEqualTo(3);
        assertThat(report.kpis().averageTicket()).isEqualByComparingTo("40.00");

        assertThat(report.monthlySeries()).hasSize(2);
        assertThat(report.monthlySeries().get(0).month()).isEqualTo(YearMonth.of(2026, 6));
        assertThat(report.monthlySeries().get(0).total()).isEqualByComparingTo("30.00");

        assertThat(report.categoryBreakdown().get(0).category()).isEqualTo("MEAT_DAIRY");
        assertThat(report.categoryBreakdown().get(0).total()).isEqualByComparingTo("50.00");

        assertThat(report.topMarkets().get(0).marketName()).isEqualTo("Bistek");
        assertThat(report.topMarkets().get(0).receiptCount()).isEqualTo(1);

        assertThat(report.topProducts().get(0).description()).isEqualTo("PICANHA");
        assertThat(report.items()).hasSize(3);
    }

    @Test
    void assemble_skipsExcludedItemsEverywhere() {
        var receipt = receipt("11111111000111", "Zaffari", LocalDateTime.of(2026, Month.JULY, 1, 9, 0), "10.00",
                item("COMPRADO", "10.00", ProductCategory.GROCERIES, false),
                item("DEVOLVIDO", "99.00", ProductCategory.GROCERIES, true));
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(receipt));

        var report = assembler.assemble(user, null, null);

        assertThat(report.kpis().itemCount()).isEqualTo(1);
        assertThat(report.items()).extracting(PurchaseReportData.ItemRow::item).containsExactly("COMPRADO");
        assertThat(report.categoryBreakdown().get(0).total()).isEqualByComparingTo("10.00");
        assertThat(report.topProducts()).extracting(PurchaseReportData.ProductSpend::description)
                .doesNotContain("DEVOLVIDO");
    }

    @Test
    void assemble_emptyHistory_yieldsZeroKpisAndEmptySeries() {
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        var report = assembler.assemble(user, null, null);

        assertThat(report.kpis().totalSpent()).isEqualByComparingTo("0");
        assertThat(report.kpis().averageTicket()).isEqualByComparingTo("0");
        assertThat(report.monthlySeries()).isEmpty();
        assertThat(report.categoryBreakdown()).isEmpty();
        assertThat(report.topMarkets()).isEmpty();
        assertThat(report.items()).isEmpty();
    }
}
