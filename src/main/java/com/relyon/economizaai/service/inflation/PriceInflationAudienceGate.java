package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.config.PriceInflationProperties.Audience;
import com.relyon.economizaai.exception.PriceInflationUnavailableException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Who "Preços e inflação" exists for: admins only until the audience setting opens it to everyone. */
@Component
@RequiredArgsConstructor
public class PriceInflationAudienceGate {

    private final PriceInflationProperties properties;

    public boolean isOpenToEveryone() {
        return properties.getAudience() == Audience.EVERYONE;
    }

    public boolean isVisibleTo(User user) {
        return isOpenToEveryone() || user.getRole() == Role.ADMIN;
    }

    public void requireVisibleTo(User user) {
        if (!isVisibleTo(user)) throw new PriceInflationUnavailableException();
    }
}
