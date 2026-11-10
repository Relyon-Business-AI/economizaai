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
import java.util.List;
import java.util.UUID;

/**
 * Resolve o ponto de referência do usuário para recortes "perto de você"
 * (promoções, mercados, melhores preços). Cascata explícita, da mais precisa
 * pra mais grosseira — a primeira etapa que der um ponto vence:
 *
 * <ol>
 *   <li>{@code DEVICE} — localização em tempo real do aparelho (lat/lng que o app
 *       mandou na requisição). É a mais precisa; só existe na chamada que passa
 *       coordenadas (ex.: scanner de código de barras dentro da loja).</li>
 *   <li>{@code PROFILE} — localização que o próprio usuário fixou no perfil
 *       ({@code home_latitude}/{@code home_longitude}, via GPS/CEP).</li>
 *   <li>{@code RECEIPTS_MEDIAN} — centro ESTIMADO das compras: mediana das
 *       coordenadas dos mercados geocodificados onde o domicílio já escaneou nota.
 *       É o dado do próprio usuário, usado só pra servir ele mesmo; nunca vai pro
 *       índice colaborativo. Mediana (não média) é robusta a uma nota de viagem.</li>
 *   <li>{@code UF_FALLBACK} — sem nenhuma coordenada do usuário, mas há uma UF
 *       majoritária das notas: usa o CENTRO (mediana) dos mercados geocodificados
 *       daquela UF. Ponto grosseiro, "estado inteiro".</li>
 *   <li>{@code NONE} — nada: sem coordenadas e sem UF.</li>
 * </ol>
 *
 * <p>{@code RECEIPTS_MEDIAN} e {@code UF_FALLBACK} são "inferidos" — ver
 * {@link ViewerLocation#isInferred()}: o FE deve sugerir "confirme sua localização".
 * A UF majoritária acompanha o resultado em todos os casos (fallback de estado).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewerLocationService {

    private final ReceiptRepository receiptRepository;
    private final MarketLocationRepository marketLocationRepository;

    public enum Source { DEVICE, PROFILE, RECEIPTS_MEDIAN, UF_FALLBACK, NONE }

    public record ViewerLocation(BigDecimal latitude, BigDecimal longitude, Source source, String uf) {
        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }

        /** True quando o ponto foi ESTIMADO (notas/UF) e não confirmado pelo usuário — o FE nudgeia. */
        public boolean isInferred() {
            return source == Source.RECEIPTS_MEDIAN || source == Source.UF_FALLBACK;
        }
    }

    /** Cascata sem coordenadas do aparelho: perfil → mediana das notas → UF → nada. */
    @Transactional(readOnly = true)
    public ViewerLocation resolve(User user) {
        return resolve(user, null, null);
    }

    /**
     * Cascata completa. Quando {@code deviceLatitude}/{@code deviceLongitude} vierem preenchidos
     * (par tudo-ou-nada — só conta se os DOIS existirem), o ponto do aparelho em tempo real vence
     * toda a cascata; senão cai pra perfil → mediana das notas → UF → nada.
     */
    @Transactional(readOnly = true)
    public ViewerLocation resolve(User user, BigDecimal deviceLatitude, BigDecimal deviceLongitude) {
        var householdId = user.getHousehold().getId();
        var uf = majorityUf(householdId);

        if (deviceLatitude != null && deviceLongitude != null) {
            return new ViewerLocation(deviceLatitude, deviceLongitude, Source.DEVICE, uf);
        }
        if (user.getHomeLatitude() != null && user.getHomeLongitude() != null) {
            return new ViewerLocation(user.getHomeLatitude(), user.getHomeLongitude(), Source.PROFILE, uf);
        }

        var receiptsMedian = receiptMarketsCentroid(householdId);
        if (receiptsMedian != null) {
            log.debug("viewer_location.receipts_median household={}", householdId);
            return new ViewerLocation(receiptsMedian.latitude(), receiptsMedian.longitude(),
                    Source.RECEIPTS_MEDIAN, uf);
        }

        var ufCentroid = uf == null ? null : stateCentroid(uf);
        if (ufCentroid != null) {
            log.debug("viewer_location.uf_fallback household={} uf={}", householdId, uf);
            return new ViewerLocation(ufCentroid.latitude(), ufCentroid.longitude(), Source.UF_FALLBACK, uf);
        }

        return new ViewerLocation(null, null, Source.NONE, uf);
    }

    /** Mediana das coordenadas dos mercados geocodificados onde o domicílio já comprou, ou null. */
    private Centroid receiptMarketsCentroid(UUID householdId) {
        var cnpjs = receiptRepository.findDistinctCnpjsByHousehold(householdId);
        if (cnpjs.isEmpty()) return null;
        return medianOf(marketLocationRepository.findAllByCnpjIn(cnpjs).stream()
                .filter(MarketLocation::hasCoordinates)
                .toList());
    }

    /** Mediana das coordenadas de TODOS os mercados geocodificados daquela UF (centro do estado), ou null. */
    private Centroid stateCentroid(String uf) {
        return medianOf(marketLocationRepository.findAllByStateAndLatitudeIsNotNull(uf));
    }

    private Centroid medianOf(List<MarketLocation> geocoded) {
        if (geocoded.isEmpty()) return null;
        var latitude = PriceIndexService.median(geocoded.stream().map(MarketLocation::getLatitude).toList());
        var longitude = PriceIndexService.median(geocoded.stream().map(MarketLocation::getLongitude).toList());
        return new Centroid(latitude, longitude);
    }

    private String majorityUf(UUID householdId) {
        var ufs = receiptRepository.findConfirmedUfsForHousehold(householdId);
        return ufs.isEmpty() ? null : ufs.get(0).name();
    }

    private record Centroid(BigDecimal latitude, BigDecimal longitude) {}
}
