package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resolve o ponto de referência do usuário para recortes "perto de você"
 * (promoções, mercados). Cascata, da mais precisa pra mais grosseira:
 *
 * <ol>
 *   <li>{@code EXPLICIT} — localização que o próprio usuário definiu (GPS/CEP).</li>
 *   <li>{@code INFERRED} — centro ESTIMADO das compras: mediana das coordenadas
 *       dos mercados geocodificados onde o domicílio já escaneou nota. É o dado
 *       do próprio usuário, usado só pra servir ele mesmo; nunca vai pro índice
 *       colaborativo. Mediana (não média) é robusta a uma nota de viagem.</li>
 *   <li>{@code NONE} — sem coordenadas; ainda pode haver uma UF majoritária das
 *       notas pra um recorte por estado.</li>
 * </ol>
 *
 * <p>A UF majoritária acompanha o resultado em todos os casos (fallback de estado).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewerLocationService {

    private final ReceiptRepository receiptRepository;
    private final MarketLocationRepository marketLocationRepository;

    public enum Source { EXPLICIT, INFERRED, NONE }

    public record ViewerLocation(BigDecimal latitude, BigDecimal longitude, Source source, String uf) {
        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }
    }

    @Transactional(readOnly = true)
    public ViewerLocation resolve(User user) {
        var householdId = user.getHousehold().getId();
        var uf = majorityUf(householdId);

        if (user.getHomeLatitude() != null && user.getHomeLongitude() != null) {
            return new ViewerLocation(user.getHomeLatitude(), user.getHomeLongitude(), Source.EXPLICIT, uf);
        }

        var inferred = inferCentroid(householdId);
        if (inferred != null) {
            return new ViewerLocation(inferred.latitude(), inferred.longitude(), Source.INFERRED, uf);
        }

        return new ViewerLocation(null, null, Source.NONE, uf);
    }

    /** Centro estimado = mediana das coordenadas dos mercados geocodificados do domicílio, ou null. */
    private Centroid inferCentroid(UUID householdId) {
        var cnpjs = receiptRepository.findDistinctCnpjsByHousehold(householdId);
        if (cnpjs.isEmpty()) return null;
        var geocoded = marketLocationRepository.findAllByCnpjIn(cnpjs).stream()
                .filter(MarketLocation::hasCoordinates)
                .toList();
        if (geocoded.isEmpty()) return null;
        var latitude = PriceIndexService.median(geocoded.stream().map(MarketLocation::getLatitude).toList());
        var longitude = PriceIndexService.median(geocoded.stream().map(MarketLocation::getLongitude).toList());
        log.debug("viewer_location.inferred household={} markets={}", householdId, geocoded.size());
        return new Centroid(latitude, longitude);
    }

    private String majorityUf(UUID householdId) {
        var ufs = receiptRepository.findConfirmedUfsForHousehold(householdId);
        return ufs.isEmpty() ? null : ufs.get(0).name();
    }

    private record Centroid(BigDecimal latitude, BigDecimal longitude) {}
}
