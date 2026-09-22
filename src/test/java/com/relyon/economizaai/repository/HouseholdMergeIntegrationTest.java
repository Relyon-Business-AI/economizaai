package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MergeCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.HouseholdMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end merge/restore against a REAL database (H2), exercising the actual
 * HouseholdMergeService over real repositories — so it catches what mocked unit tests
 * can't: the per-household UNIQUE(chave) constraint firing on a real collision, the
 * origin_household_id provenance surviving a move, and the full merge -> restore
 * round-trip leaving each household with exactly its own data again.
 *
 * Imports just the merge service (a @DataJpaTest slice doesn't pick up @Service beans).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(HouseholdMergeService.class)
class HouseholdMergeIntegrationTest {

    @Autowired private HouseholdMergeService mergeService;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private Household hA;   // host / target
    private Household hB;   // joiner / origin
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        hA = householdRepository.save(Household.builder().inviteCode("HHHA01").build());
        hB = householdRepository.save(Household.builder().inviteCode("HHHB01").build());
        userA = saveUser("alice@test.com", hA);
        userB = saveUser("bob@test.com", hB);
    }

    private User saveUser(String email, Household h) {
        return userRepository.save(User.builder()
                .name(email).email(email).password("x").household(h)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Receipt seedReceipt(User owner, Household home, String chave) {
        return receiptRepository.save(Receipt.builder()
                .user(owner).household(home).originHousehold(home)
                .chaveAcesso(chave).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("100.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build());
    }

    @Test
    void merge_movesJoinersReceiptsIntoTargetKeepingOrigin() {
        seedReceipt(userB, hB, "CHB1");
        seedReceipt(userB, hB, "CHB2");

        var result = mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));

        assertEquals(2, result.moved());
        assertEquals(0, result.shadowed());
        // both now live in hA...
        assertEquals(2, receiptRepository.findAllByHouseholdId(hA.getId()).size());
        assertTrue(receiptRepository.findAllByHouseholdId(hB.getId()).isEmpty());
        // ...but provenance still points at hB
        assertTrue(receiptRepository.findAllByOriginHouseholdId(hB.getId()).stream()
                .allMatch(r -> r.getOriginHousehold().getId().equals(hB.getId())));
    }

    @Test
    void merge_collisionOnSameChaveKeepsHostAndParksJoiner() {
        seedReceipt(userA, hA, "SHARED");   // host already has it
        seedReceipt(userB, hB, "SHARED");   // joiner's duplicate
        seedReceipt(userB, hB, "UNIQUEB");

        var result = mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));

        assertEquals(1, result.moved());     // only UNIQUEB
        assertEquals(1, result.shadowed());  // SHARED dup parked
        // host still has exactly one SHARED (no constraint violation)
        var inA = receiptRepository.findAllByHouseholdId(hA.getId());
        assertEquals(2, inA.size());   // SHARED (host's) + UNIQUEB
        assertEquals(1, inA.stream().filter(r -> r.getChaveAcesso().equals("SHARED")).count());
        // joiner's SHARED dup is still parked on hB
        var inB = receiptRepository.findAllByHouseholdId(hB.getId());
        assertEquals(1, inB.size());
        assertEquals("SHARED", inB.get(0).getChaveAcesso());
    }

    @Test
    void mergeThenRestore_returnsJoinersDataIncludingShadowed() {
        seedReceipt(userA, hA, "SHARED");
        seedReceipt(userB, hB, "SHARED");    // will be shadowed
        seedReceipt(userB, hB, "UNIQUEB");   // will move

        mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));
        // restore everything whose origin is hB back to hB
        var restored = mergeService.restoreOriginals(hB);

        // UNIQUEB comes back; SHARED was never moved (parked) so it's already home.
        // Either way, hB ends with BOTH of its original receipts.
        List<Receipt> inB = receiptRepository.findAllByHouseholdId(hB.getId());
        var chaves = inB.stream().map(Receipt::getChaveAcesso).sorted().toList();
        assertEquals(List.of("SHARED", "UNIQUEB"), chaves);
        // hA keeps only its own SHARED
        var inA = receiptRepository.findAllByHouseholdId(hA.getId());
        assertEquals(1, inA.size());
        assertEquals("SHARED", inA.get(0).getChaveAcesso());
        assertTrue(restored.moved() >= 1);
    }

    @Test
    void copyUserData_duplicatesGrantorReceiptsIntoDestination() {
        // bob's receipts currently live in the shared household hA (post-merge state)
        seedReceipt(userB, hA, "CPB1");
        seedReceipt(userB, hA, "CPB2");
        var destination = householdRepository.save(Household.builder().inviteCode("DEST01").build());

        var copied = mergeService.copyUserData(userB.getId(), hA, destination);

        assertEquals(2, copied);
        // originals stay in hA; copies appear in destination as NEW rows
        assertEquals(2, receiptRepository.findAllByHouseholdId(hA.getId()).size());
        var inDest = receiptRepository.findAllByHouseholdId(destination.getId());
        assertEquals(2, inDest.size());
        assertTrue(inDest.stream().allMatch(r -> r.getOriginHousehold().getId().equals(destination.getId())));
    }

    @Test
    void copyUserData_skipsChaveAlreadyInDestination() {
        seedReceipt(userB, hA, "DUP");
        var destination = householdRepository.save(Household.builder().inviteCode("DEST02").build());
        seedReceipt(userA, destination, "DUP");   // destination already has this chave

        var copied = mergeService.copyUserData(userB.getId(), hA, destination);

        assertEquals(0, copied);   // host (destination) wins, nothing copied
        assertEquals(1, receiptRepository.findAllByHouseholdId(destination.getId()).size());
    }

    @Test
    void merge_emptyJoinerIsNoOp() {
        var result = mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));
        assertEquals(0, result.moved());
        assertEquals(0, result.shadowed());
        assertTrue(receiptRepository.findAllByHouseholdId(hA.getId()).isEmpty());
    }

    @Test
    void merge_repeatedJoinIsIdempotentOnChave() {
        seedReceipt(userB, hB, "ONCE");
        mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));
        // a second merge from the (now-empty) origin moves nothing
        var second = mergeService.merge(hB, hA, Set.of(MergeCategory.RECEIPTS));
        assertEquals(0, second.moved());
        assertEquals(1, receiptRepository.findAllByHouseholdId(hA.getId()).size());
    }
}
