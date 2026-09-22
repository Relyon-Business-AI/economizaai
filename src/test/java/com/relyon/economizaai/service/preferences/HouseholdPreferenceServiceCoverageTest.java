package com.relyon.economizaai.service.preferences;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.Confidence;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.ManualBrandPreference;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdPreferenceServiceCoverageTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ManualBrandPreferenceRepository manualBrandPreferenceRepository;

    private CollaborativeProperties properties;
    private HouseholdPreferenceService service;
    private User user;
    private Household household;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new HouseholdPreferenceService(receiptItemRepository, manualBrandPreferenceRepository, properties);
        lenient().when(manualBrandPreferenceRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void emptyWhenNoHistoryAndNoManualOverrides() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of());

        assertTrue(service.derivePreferences(user).isEmpty());
    }

    @Test
    void manualOnlyEntryWhenNoHistoryForThatGeneric() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of());
        when(manualBrandPreferenceRepository.findAllByHouseholdId(household.getId()))
                .thenReturn(List.of(manualPreference("Cafe", "Pilao", BrandStrength.MUST_HAVE)));

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        var cafe = prefs.get(0);
        assertEquals("Cafe", cafe.genericName());
        assertEquals("Pilao", cafe.topBrand());
        assertEquals(BrandStrength.MUST_HAVE, cafe.brandStrength());
        assertNull(cafe.preferredPackSize());
        assertTrue(cafe.brandDistribution().isEmpty());
        assertEquals(0, cafe.sampleSize());
        assertEquals(Confidence.LOW, cafe.confidence());
        assertNull(cafe.topBrandShare());
    }

    @Test
    void manualOverrideWinsOverDerivedAndKeepsPackStats() {
        var italac = product("Leite", "Italac", new BigDecimal("1"), "L");
        var items = repeat(purchase(italac), 10);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);
        when(manualBrandPreferenceRepository.findAllByHouseholdId(household.getId()))
                .thenReturn(List.of(manualPreference("Leite", "Elege", BrandStrength.PREFERRED)));

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        var leite = prefs.get(0);
        assertEquals("Elege", leite.topBrand(), "manual brand overrides the derived Italac");
        assertEquals(BrandStrength.PREFERRED, leite.brandStrength());
        assertEquals(0, leite.preferredPackSize().compareTo(new BigDecimal("1")), "derived pack-size retained");
        assertEquals(10, leite.sampleSize(), "derived sample stats retained");
        assertNull(leite.topBrandShare(),
                "Elege absent from derived distribution (all purchases Italac) -> share null");
    }

    @Test
    void manualOverrideKeepsShareWhenBrandPresentInDistribution() {
        var italac = product("Leite", "Italac", new BigDecimal("1"), "L");
        var elege = product("Leite", "elege", new BigDecimal("1"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(italac), 7));
        items.addAll(repeat(purchase(elege), 3));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);
        when(manualBrandPreferenceRepository.findAllByHouseholdId(household.getId()))
                .thenReturn(List.of(manualPreference("Leite", "Elege", BrandStrength.MUST_HAVE)));

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        var leite = prefs.get(0);
        assertEquals("Elege", leite.topBrand());
        assertNotNull(leite.topBrandShare(), "case-insensitive match against distribution 'elege'");
        assertEquals(0, leite.topBrandShare().compareTo(new BigDecimal("0.3000")));
    }

    @Test
    void highConfidenceWhenSampleAtLeastFifteen() {
        var leite = product("Leite", "Italac", new BigDecimal("1"), "L");
        var items = repeat(purchase(leite), 15);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals(Confidence.HIGH, prefs.get(0).confidence());
    }

    @Test
    void brandOnlyPreferenceWhenPackSizeDataAbsent() {
        var noPack = Product.builder()
                .id(UUID.randomUUID())
                .normalizedName("Cafe Pilao")
                .genericName("Cafe")
                .brand("Pilao")
                .build();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(repeat(purchase(noPack), 10));

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        var cafe = prefs.get(0);
        assertNull(cafe.preferredPackSize(), "no pack size data -> pack fields stay null");
        assertEquals("Pilao", cafe.topBrand());
        assertEquals(BrandStrength.MUST_HAVE, cafe.brandStrength());
    }

    @Test
    void skipsGenericWithNeitherPackSizeNorBrand() {
        var bare = Product.builder()
                .id(UUID.randomUUID())
                .normalizedName("Generico")
                .genericName("Generico")
                .build();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(repeat(purchase(bare), 10));

        assertTrue(service.derivePreferences(user).isEmpty(),
                "no pack-size and no brand -> nothing derived");
    }

    @Test
    void skipsItemsWithNullProduct() {
        var withProduct = product("Leite", "Italac", new BigDecimal("1"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(withProduct), 5));
        items.add(purchaseWithNullProduct());
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size(), "null-product item is filtered out, the 5 Leite still derive");
        assertEquals("Leite", prefs.get(0).genericName());
    }

    private ManualBrandPreference manualPreference(String genericName, String brand, BrandStrength strength) {
        return ManualBrandPreference.builder()
                .id(UUID.randomUUID())
                .household(household)
                .genericName(genericName)
                .brand(brand)
                .strength(strength)
                .build();
    }

    private Product product(String genericName, String brand, BigDecimal packSize, String packUnit) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(genericName + " " + brand)
                .genericName(genericName)
                .brand(brand)
                .packSize(packSize)
                .packUnit(packUnit)
                .build();
    }

    private ReceiptItem purchase(Product product) {
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(household).build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .product(product)
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(BigDecimal.ONE)
                .build();
    }

    private ReceiptItem purchaseWithNullProduct() {
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(household).build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .product(null)
                .lineNumber(1)
                .rawDescription("unmatched")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(BigDecimal.ONE)
                .build();
    }

    private List<ReceiptItem> repeat(ReceiptItem template, int times) {
        var out = new ArrayList<ReceiptItem>(times);
        for (int index = 0; index < times; index++) {
            out.add(ReceiptItem.builder()
                    .id(UUID.randomUUID())
                    .receipt(template.getReceipt())
                    .product(template.getProduct())
                    .lineNumber(index + 1)
                    .rawDescription(template.getRawDescription())
                    .quantity(BigDecimal.ONE)
                    .unitPrice(BigDecimal.ONE)
                    .totalPrice(BigDecimal.ONE)
                    .build());
        }
        return out;
    }
}
