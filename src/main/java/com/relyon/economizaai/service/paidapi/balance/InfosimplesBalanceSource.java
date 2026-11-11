package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.service.sefaz.InfosimplesService;
import com.relyon.economizaai.service.sefaz.InfosimplesService.InfosimplesAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** Infosimples prepaid saldo (R$), read through the free account endpoint the finance panel already uses. */
@Component
@RequiredArgsConstructor
public class InfosimplesBalanceSource implements ProviderBalanceSource {

    private final Optional<InfosimplesService> infosimples;

    @Override
    public String providerKey() {
        return "INFOSIMPLES";
    }

    @Override
    public String displayName() {
        return "Infosimples";
    }

    @Override
    public String currencySymbol() {
        return "R$";
    }

    @Override
    public boolean isConfigured() {
        return infosimples.isPresent();
    }

    @Override
    public Optional<BigDecimal> fetchBalance() {
        return infosimples.flatMap(InfosimplesService::fetchAccount).map(InfosimplesAccount::balance);
    }

    @Override
    public BigDecimal exhaustedAtOrBelow() {
        return new BigDecimal("0.24");
    }

    @Override
    public BigDecimal minimumTopUp() {
        return BigDecimal.ONE;
    }

    @Override
    public String whatStops() {
        return "as consultas pagas de nota param (CE e o resgate de portais fora do ar)";
    }
}
