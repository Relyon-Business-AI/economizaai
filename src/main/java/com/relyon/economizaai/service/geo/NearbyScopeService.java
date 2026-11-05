package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        var radiusKm = radiusKmOverride != null
                ? radiusKmOverride
                : properties.getCollaborative().getBestMarketsNearbyRadiusKm();
        return new NearbyScope(viewerLocationService.resolve(user), watchedMarketService.watchedCnpjs(user), radiusKm);
    }
}
