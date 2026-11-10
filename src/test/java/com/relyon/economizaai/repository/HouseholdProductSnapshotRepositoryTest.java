package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdProductSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("test")
class HouseholdProductSnapshotRepositoryTest {

    @Autowired private HouseholdProductSnapshotRepository repository;

    private final UUID householdId = UUID.randomUUID();

    @Test
    void uniquePerHouseholdAndSnapshotKey() {
        var productId = UUID.randomUUID();
        repository.saveAndFlush(snapshot(productId.toString(), new BigDecimal("3.49"), OffsetDateTime.now()));
        var duplicate = snapshot(productId.toString(), new BigDecimal("4.00"), OffsetDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
    }

    @Test
    void findByHouseholdIdAndSnapshotKey_roundTrips() {
        repository.saveAndFlush(snapshot("type:arroz", new BigDecimal("5.00"), OffsetDateTime.now()));

        var found = repository.findByHouseholdIdAndSnapshotKey(householdId, "type:arroz");

        assertThat(found).isPresent();
        assertThat(found.get().getLastBestUnitPrice()).isEqualByComparingTo("5.00");
    }

    @Test
    void listOrdersNewestConsultFirstAndCaps() {
        var older = snapshot("type:arroz", new BigDecimal("5.00"), OffsetDateTime.now().minusDays(2));
        var newer = snapshot("type:feijao", new BigDecimal("8.00"), OffsetDateTime.now());
        repository.saveAndFlush(older);
        repository.saveAndFlush(newer);

        var rows = repository.findByHouseholdIdOrderByLastConsultedAtDesc(householdId, PageRequest.of(0, 100));

        assertThat(rows).extracting(HouseholdProductSnapshot::getSnapshotKey)
                .containsExactly("type:feijao", "type:arroz");
    }

    private HouseholdProductSnapshot snapshot(String snapshotKey, BigDecimal price, OffsetDateTime consultedAt) {
        return HouseholdProductSnapshot.builder()
                .householdId(householdId)
                .snapshotKey(snapshotKey)
                .lastBestUnitPrice(price)
                .lastBestScope("NEARBY")
                .scanCount(1)
                .lastConsultedAt(consultedAt)
                .build();
    }
}
