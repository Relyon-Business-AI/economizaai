package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Builds the viewer's {@link NearbyScope}: resolved location + watched markets + default radius. */
@Service
@RequiredArgsConstructor
public class NearbyScopeService {

    private final ViewerLocationService viewerLocationService;
    private final WatchedMarketService watchedMarketService;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public NearbyScope forUser(User user) {
        return forUser(user, null);
    }

    /** {@code radiusKmOverride} null = the default "perto de você" radius. */
    @Transactional(readOnly = true)
    public NearbyScope forUser(User user, Double radiusKmOverride) {
        return forUser(user, radiusKmOverride, null, null);
    }

    /**
     * Same, with the device's live coordinates (both or neither) taking precedence over the
     * profile → receipts median → UF cascade.
     */
    @Transactional(readOnly = true)
    public NearbyScope forUser(User user, Double radiusKmOverride, BigDecimal deviceLatitude, BigDecimal deviceLongitude) {
        var radiusKm = radiusKmOverride != null ? radiusKmOverride : defaultRadiusKm();
        return new NearbyScope(viewerLocationService.resolve(user, deviceLatitude, deviceLongitude),
                watchedMarketService.watchedCnpjs(user), radiusKm);
    }

    public double defaultRadiusKm() {
        return properties.getCollaborative().getBestMarketsNearbyRadiusKm();
    }
}
