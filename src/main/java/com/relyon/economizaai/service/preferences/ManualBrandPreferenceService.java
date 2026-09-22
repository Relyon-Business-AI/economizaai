package com.relyon.economizaai.service.preferences;

import com.relyon.economizaai.dto.request.SetBrandPreferenceRequest;
import com.relyon.economizaai.model.ManualBrandPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD over per-household manual brand preferences. Merging with derived
 * preferences happens in {@link HouseholdPreferenceService} — this service
 * is just the storage layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualBrandPreferenceService {

    private final ManualBrandPreferenceRepository repository;

    @Transactional(readOnly = true)
    public List<ManualBrandPreference> listForHousehold(User user) {
        return repository.findAllByHouseholdId(user.getHousehold().getId());
    }

    /** Idempotent upsert — sets the brand for a generic, replacing any prior choice. */
    @Transactional
    public ManualBrandPreference set(User user, String genericName, SetBrandPreferenceRequest request) {
        var householdId = user.getHousehold().getId();
        var existing = repository.findByHouseholdIdAndGenericName(householdId, genericName);
        var entity = existing.orElseGet(() -> ManualBrandPreference.builder()
                .household(user.getHousehold())
                .genericName(genericName)
                .build());
        entity.setBrand(request.brand());
        entity.setStrength(request.strength());
        var saved = repository.save(entity);
        log.info("preference.manual.set household={} generic='{}' brand='{}' strength={}",
                householdId, genericName, request.brand(), request.strength());
        return saved;
    }

    /** Best-effort delete — silently no-ops when there's nothing to remove. */
    @Transactional
    public void clear(User user, String genericName) {
        var householdId = user.getHousehold().getId();
        repository.findByHouseholdIdAndGenericName(householdId, genericName)
                .ifPresent(entity -> {
                    repository.delete(entity);
                    log.info("preference.manual.cleared household={} generic='{}'", householdId, genericName);
                });
    }
}
