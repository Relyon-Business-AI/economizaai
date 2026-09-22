package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.exception.InvalidCnpjException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
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
