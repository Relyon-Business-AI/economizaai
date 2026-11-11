package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.exception.InvalidCnpjException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.MarketChain;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.repository.MarketChainRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MarketLocationRepository.MarketTradeName;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketNameServiceTest {

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final String CNPJ = "12345678000199";

    @Mock private HouseholdMarketAliasRepository aliasRepository;
    @Mock private MarketChainRepository marketChainRepository;
    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private HouseholdCacheGen householdCacheGen;
    @InjectMocks private MarketNameService service;

    @Test
    void resolve_returnsFallbackWhenNoOverride() {
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.empty());

        assertEquals("Zaffari", service.resolve(HOUSEHOLD_ID, CNPJ, "Zaffari"));
    }

    @Test
    void resolve_returnsOverrideWhenPresent() {
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ))
                .thenReturn(Optional.of(alias("Zaffari de casa")));

        assertEquals("Zaffari de casa", service.resolve(HOUSEHOLD_ID, CNPJ, "Zaffari"));
    }

    @Test
    void resolve_returnsFallbackWhenHouseholdIdNull() {
        assertEquals("Zaffari", service.resolve(null, CNPJ, "Zaffari"));
    }

    @Test
    void resolve_returnsFallbackWhenCnpjNull() {
        assertEquals("Zaffari", service.resolve(HOUSEHOLD_ID, null, "Zaffari"));
    }

    @Test
    void resolveNames_returnsOnlyRenamedCnpjs() {
        var cnpjs = List.of(CNPJ, "99999999000100");
        when(aliasRepository.findAllByHouseholdIdAndMarketCnpjIn(HOUSEHOLD_ID, cnpjs))
                .thenReturn(List.of(alias("Zaffari de casa")));

        var overrides = service.resolveNames(HOUSEHOLD_ID, cnpjs);

        assertEquals(1, overrides.size());
        assertEquals("Zaffari de casa", overrides.get(CNPJ));
    }

    @Test
    void resolveNames_emptyWhenNoCnpjs() {
        assertTrue(service.resolveNames(HOUSEHOLD_ID, List.of()).isEmpty());
    }

    @Test
    void resolveNames_emptyWhenHouseholdIdNull() {
        assertTrue(service.resolveNames(null, List.of(CNPJ)).isEmpty());
    }

    @Test
    void applyOverride_returnsOverrideWhenPresent() {
        var overrides = Map.of(CNPJ, "Zaffari de casa");

        assertEquals("Zaffari de casa", service.applyOverride(overrides, CNPJ, "Zaffari"));
    }

    @Test
    void applyOverride_returnsFallbackWhenAbsent() {
        assertEquals("Zaffari", service.applyOverride(Map.of(), CNPJ, "Zaffari"));
    }

    @Test
    void applyOverride_returnsFallbackWhenOverridesNull() {
        assertEquals("Zaffari", service.applyOverride(null, CNPJ, "Zaffari"));
    }

    @Test
    void setName_createsNewAliasWhenNonePresent() {
        var user = user();
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.empty());
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.setName(user, CNPJ, "  Zaffari de casa  ");

        assertEquals("Zaffari de casa", result);
        var captor = ArgumentCaptor.forClass(HouseholdMarketAlias.class);
        verify(aliasRepository).save(captor.capture());
        assertEquals(CNPJ, captor.getValue().getMarketCnpj());
        assertEquals("Zaffari de casa", captor.getValue().getCustomName());
        assertEquals(HOUSEHOLD_ID, captor.getValue().getHousehold().getId());
    }

    @Test
    void setName_updatesExistingAlias() {
        var user = user();
        var existing = alias("Velho nome");
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.of(existing));
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.setName(user, CNPJ, "Novo nome");

        assertEquals("Novo nome", result);
        assertEquals("Novo nome", existing.getCustomName());
        verify(aliasRepository).save(existing);
    }

    @Test
    void setName_normalizesFormattedCnpjToFourteenDigits() {
        var user = user();
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.empty());
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.setName(user, "12.345.678/0001-99", "Zaffari de casa");

        var captor = ArgumentCaptor.forClass(HouseholdMarketAlias.class);
        verify(aliasRepository).save(captor.capture());
        assertEquals(CNPJ, captor.getValue().getMarketCnpj());
    }

    @Test
    void setName_rejectsTooLongCnpj() {
        var user = user();

        // The live bug: a >14-char value (here an unresolved test placeholder)
        // hit the DB and overflowed VARCHAR(14) as a 500. Now a clean 400.
        assertThrows(InvalidCnpjException.class,
                () -> service.setName(user, "{{e2eMarketCnpj}}", "Mercado"));
        verify(aliasRepository, never()).save(any());
    }

    @Test
    void setName_rejectsCnpjWithFewerThanFourteenDigits() {
        var user = user();

        assertThrows(InvalidCnpjException.class, () -> service.setName(user, "123", "Mercado"));
        verify(aliasRepository, never()).save(any());
    }

    @Test
    void clearName_normalizesFormattedCnpj() {
        var user = user();

        service.clearName(user, "12.345.678/0001-99");

        verify(aliasRepository).deleteByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ);
    }

    @Test
    void clearName_rejectsInvalidCnpj() {
        var user = user();

        assertThrows(InvalidCnpjException.class, () -> service.clearName(user, "{{e2eMarketCnpj}}"));
        verify(aliasRepository, never()).deleteByHouseholdIdAndMarketCnpj(any(), any());
    }

    @Test
    void clearName_deletesAlias() {
        var user = user();

        service.clearName(user, CNPJ);

        verify(aliasRepository).deleteByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ);
    }

    // --- friendly-name cascade + cleaning heuristic ---

    @Test
    void cleanLegalName_stripsComercioEIndustriaAndTitleCases() {
        assertEquals("Companhia Zaffari",
                MarketNameService.cleanLegalName("COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void cleanLegalName_stripsSaAndLtdaSuffixes() {
        assertEquals("Carrefour", MarketNameService.cleanLegalName("CARREFOUR COMERCIO E INDUSTRIA S/A"));
        assertEquals("Atacadao", MarketNameService.cleanLegalName("ATACADAO S.A."));
        // "S A" space-separated (common razão social) is stripped too
        assertEquals("Mateus", MarketNameService.cleanLegalName("MATEUS SUPERMERCADOS S A"));
    }

    @Test
    void cleanLegalName_stripsWmsSupermercadosBoilerplate() {
        // WMS Supermercados do Brasil Ltda (BIG/Bompreço razão social)
        assertEquals("Wms do Brasil",
                MarketNameService.cleanLegalName("WMS SUPERMERCADOS DO BRASIL LTDA"));
    }

    @Test
    void cleanLegalName_keepsConnectivesLowercase() {
        assertEquals("Pague Menos",
                MarketNameService.cleanLegalName("DISTRIBUIDORA DE ALIMENTOS PAGUE MENOS LTDA"));
        assertEquals("Mercado do Ze",
                MarketNameService.cleanLegalName("MERCADO DO ZE LTDA"));
    }

    @Test
    void cleanLegalName_fallsBackToTitleCasedOriginalWhenOnlyBoilerplate() {
        assertEquals("Supermercados Ltda", MarketNameService.cleanLegalName("SUPERMERCADOS LTDA"));
    }

    @Test
    void cleanLegalName_nullAndBlankPassThrough() {
        assertEquals(null, MarketNameService.cleanLegalName(null));
        assertEquals("   ", MarketNameService.cleanLegalName("   "));
    }

    @Test
    void resolve_usesTradeNameWhenHouseholdHasNoRename() {
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.empty());
        when(marketLocationRepository.findTradeNamesByCnpjIn(List.of(CNPJ)))
                .thenReturn(List.of(tradeName(CNPJ, "ASSAI ATACADISTA")));

        assertEquals("Assai Atacadista", service.resolve(HOUSEHOLD_ID, CNPJ, "SENDAS DISTRIBUIDORA S/A"));
    }

    @Test
    void resolve_prefersHouseholdRenameOverTradeName() {
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ))
                .thenReturn(Optional.of(alias("Assaí da esquina")));

        assertEquals("Assaí da esquina", service.resolve(HOUSEHOLD_ID, CNPJ, "SENDAS DISTRIBUIDORA S/A"));
        verify(marketLocationRepository, never()).findTradeNamesByCnpjIn(any());
    }

    @Test
    void resolve_cleansRazaoSocialWhenNoRenameAndNoTradeName() {
        when(aliasRepository.findByHouseholdIdAndMarketCnpj(HOUSEHOLD_ID, CNPJ)).thenReturn(Optional.empty());

        assertEquals("Companhia Zaffari",
                service.resolve(HOUSEHOLD_ID, CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void resolve_usesTradeNameEvenWithoutHousehold() {
        when(marketLocationRepository.findTradeNamesByCnpjIn(List.of(CNPJ)))
                .thenReturn(List.of(tradeName(CNPJ, "GIASSI SUPERMERCADO")));

        assertEquals("Giassi Supermercado", service.resolve(null, CNPJ, "GIASSI & CIA LTDA"));
    }

    @Test
    void resolveNames_mergesTradeNamesUnderHouseholdRenames() {
        var otherCnpj = "99999999000100";
        var cnpjs = List.of(CNPJ, otherCnpj);
        when(marketLocationRepository.findTradeNamesByCnpjIn(cnpjs))
                .thenReturn(List.of(tradeName(CNPJ, "ASSAI ATACADISTA"), tradeName(otherCnpj, "GIASSI SUPERMERCADO")));
        when(aliasRepository.findAllByHouseholdIdAndMarketCnpjIn(HOUSEHOLD_ID, cnpjs))
                .thenReturn(List.of(alias("Assaí da esquina")));

        var names = service.resolveNames(HOUSEHOLD_ID, cnpjs);

        assertEquals("Assaí da esquina", names.get(CNPJ));
        assertEquals("Giassi Supermercado", names.get(otherCnpj));
    }

    @Test
    void resolveNames_ignoresFillerTradeNames() {
        when(marketLocationRepository.findTradeNamesByCnpjIn(List.of(CNPJ)))
                .thenReturn(List.of(tradeName(CNPJ, "********")));

        assertTrue(service.resolveNames(null, List.of(CNPJ)).isEmpty());
    }

    @Test
    void applyOverride_cleansRazaoSocialWhenMarketHasNoName() {
        assertEquals("Companhia Zaffari",
                service.applyOverride(Map.of(), CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void isUsableTradeName_requiresRealLetters() {
        assertTrue(MarketNameService.isUsableTradeName("ASSAI ATACADISTA"));
        assertFalse(MarketNameService.isUsableTradeName("********"));
        assertFalse(MarketNameService.isUsableTradeName("0"));
        assertFalse(MarketNameService.isUsableTradeName(null));
    }

    @Test
    void formatTradeName_titleCasesAndKeepsConnectivesLowercase() {
        assertEquals("Mercadinho da Praça", MarketNameService.formatTradeName(" MERCADINHO DA PRAÇA "));
    }

    private static MarketTradeName tradeName(String cnpj, String tradeName) {
        return new MarketTradeName() {
            @Override public String getCnpj() {
                return cnpj;
            }

            @Override public String getTradeName() {
                return tradeName;
            }
        };
    }

    @Test
    void friendlyName_prefersHouseholdRenameOverChainAndClean() {
        var overrides = Map.of(CNPJ, "Zaffari de casa");
        var chainNames = Map.of(CNPJ.substring(0, 8), "Zaffari");

        assertEquals("Zaffari de casa",
                service.friendlyName(overrides, chainNames, CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void friendlyName_usesChainNameWhenNoRename() {
        var chainNames = Map.of(CNPJ.substring(0, 8), "Zaffari");

        assertEquals("Zaffari",
                service.friendlyName(Map.of(), chainNames, CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void friendlyName_fallsBackToCleanedRazaoSocialWhenNoChain() {
        assertEquals("Companhia Zaffari",
                service.friendlyName(Map.of(), Map.of(), CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void friendlyName_singleValue_loadsMapsAndResolvesChain() {
        when(aliasRepository.findAllByHouseholdIdAndMarketCnpjIn(HOUSEHOLD_ID, List.of(CNPJ)))
                .thenReturn(List.of());
        when(marketChainRepository.findByCnpjRoot(CNPJ.substring(0, 8)))
                .thenReturn(Optional.of(chain("Zaffari")));

        assertEquals("Zaffari",
                service.friendlyName(HOUSEHOLD_ID, CNPJ, "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"));
    }

    @Test
    void resolveChainNames_mapsRootToName() {
        when(marketChainRepository.findByCnpjRoot(CNPJ.substring(0, 8)))
                .thenReturn(Optional.of(chain("Zaffari")));

        var names = service.resolveChainNames(List.of(CNPJ));

        assertEquals(1, names.size());
        assertEquals("Zaffari", names.get(CNPJ.substring(0, 8)));
    }

    private MarketChain chain(String name) {
        return MarketChain.builder().cnpjRoot(CNPJ.substring(0, 8)).name(name).build();
    }

    private HouseholdMarketAlias alias(String customName) {
        return HouseholdMarketAlias.builder()
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .marketCnpj(CNPJ)
                .customName(customName)
                .build();
    }

    private User user() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("u@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
    }
}
