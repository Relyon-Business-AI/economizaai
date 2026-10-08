package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.SubmitMerchantClaimRequest;
import com.relyon.economizaai.exception.MerchantChainAlreadyGrantedException;
import com.relyon.economizaai.exception.MerchantClaimAlreadyOpenException;
import com.relyon.economizaai.exception.MerchantClaimAlreadyResolvedException;
import com.relyon.economizaai.exception.MerchantClaimCodeExpiredException;
import com.relyon.economizaai.exception.MerchantClaimCodeInvalidException;
import com.relyon.economizaai.exception.MerchantClaimNotVerifiableException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.MerchantClaim;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantClaimStatus;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.MerchantClaimRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.auth.AuthEmailSender;
import com.relyon.economizaai.service.auth.CodeHasher;
import com.relyon.economizaai.service.geo.CnpjActivityClient;
import com.relyon.economizaai.service.geo.CnpjActivityClient.CnpjLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.relyon.economizaai.time.BrazilClock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantClaimServiceTest {

    private static final String CNPJ = "93015006000101";
    private static final String CHAIN_ROOT = "93015006";

    @Mock private MerchantClaimRepository claimRepository;
    @Mock private MerchantAccessRepository merchantAccessRepository;
    @Mock private UserRepository userRepository;
    @Mock private CnpjActivityClient cnpjActivityClient;
    @Mock private AuthEmailSender authEmailSender;
    @Mock private ContactService contactService;
    @Mock private MerchantSubscriptionService merchantSubscriptionService;

    private User claimant;
    private MerchantClaimService service;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        service = new MerchantClaimService(claimRepository, merchantAccessRepository, userRepository,
                cnpjActivityClient, authEmailSender, contactService, merchantSubscriptionService, properties);
        claimant = User.builder().id(UUID.randomUUID()).name("Dono do Mercado")
                .email("dono@economizaai.app").role(Role.USER).build();
        lenient().when(claimRepository.save(any(MerchantClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private SubmitMerchantClaimRequest request() {
        return new SubmitMerchantClaimRequest(CNPJ, null, null);
    }

    private CnpjLookup lookupWithEmail() {
        return new CnpjLookup(MerchantSegment.SUPERMARKET, null, List.of("4711302"),
                "financeiro@mercadoteste.com.br", "MERCADO TESTE LTDA");
    }

    @Test
    void submit_companyEmailFound_sendsCodeAndAwaits() {
        when(cnpjActivityClient.lookup(CNPJ)).thenReturn(lookupWithEmail());

        var response = service.submit(claimant, request());

        assertThat(response.status()).isEqualTo(MerchantClaimStatus.AWAITING_CODE);
        assertThat(response.companyName()).isEqualTo("MERCADO TESTE LTDA");
        verify(authEmailSender).sendMerchantClaimCode(eq("financeiro@mercadoteste.com.br"), any(), anyString(), anyInt());

        var savedCaptor = ArgumentCaptor.forClass(MerchantClaim.class);
        verify(claimRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getVerificationCodeHash()).hasSize(64);
        assertThat(savedCaptor.getValue().getCodeExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void submit_noCompanyEmail_queuesForAdminReview() {
        when(cnpjActivityClient.lookup(CNPJ)).thenReturn(
                new CnpjLookup(MerchantSegment.SUPERMARKET, null, List.of("4711302"), null, "MERCADO SEM EMAIL"));

        var response = service.submit(claimant, request());

        assertThat(response.status()).isEqualTo(MerchantClaimStatus.PENDING_REVIEW);
        verify(authEmailSender, never()).sendMerchantClaimCode(any(), any(), any(), anyInt());
        verify(contactService).notifyAdmin(anyString(), anyString());
    }

    @Test
    void submit_chainAlreadyGranted_refused() {
        when(merchantAccessRepository.existsByUserIdAndCnpjRoot(claimant.getId(), CHAIN_ROOT)).thenReturn(true);

        assertThrows(MerchantChainAlreadyGrantedException.class, () -> service.submit(claimant, request()));
        verify(claimRepository, never()).save(any());
    }

    @Test
    void submit_openClaimExists_refused() {
        when(claimRepository.existsByUserIdAndCnpjRootAndStatusIn(eq(claimant.getId()), eq(CHAIN_ROOT), any()))
                .thenReturn(true);

        assertThrows(MerchantClaimAlreadyOpenException.class, () -> service.submit(claimant, request()));
    }

    private MerchantClaim awaitingClaim(String code) {
        return MerchantClaim.builder()
                .id(UUID.randomUUID()).user(claimant).cnpj(CNPJ).cnpjRoot(CHAIN_ROOT)
                .status(MerchantClaimStatus.AWAITING_CODE)
                .verificationCodeHash(CodeHasher.sha256(code))
                .codeExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    void verify_correctCode_promotesGrantsAndOpensSubscription() {
        var claim = awaitingClaim("123456");
        when(claimRepository.findByIdAndUserId(claim.getId(), claimant.getId())).thenReturn(Optional.of(claim));

        var response = service.verify(claimant, claim.getId(), "123456");

        assertThat(response.status()).isEqualTo(MerchantClaimStatus.APPROVED);
        assertThat(claimant.getRole()).isEqualTo(Role.MERCHANT);
        verify(userRepository).save(claimant);
        verify(merchantAccessRepository).save(any(MerchantAccess.class));
        verify(merchantSubscriptionService).ensureForChain(CHAIN_ROOT);
    }

    @Test
    void verify_wrongCode_countsAttemptAndThrows() {
        var claim = awaitingClaim("123456");
        when(claimRepository.findByIdAndUserId(claim.getId(), claimant.getId())).thenReturn(Optional.of(claim));

        assertThrows(MerchantClaimCodeInvalidException.class,
                () -> service.verify(claimant, claim.getId(), "000000"));

        assertThat(claim.getCodeAttempts()).isEqualTo(1);
        assertThat(claimant.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void verify_expiredCode_closesClaim() {
        var claim = awaitingClaim("123456");
        claim.setCodeExpiresAt(BrazilClock.nowDateTime().minusMinutes(1));
        when(claimRepository.findByIdAndUserId(claim.getId(), claimant.getId())).thenReturn(Optional.of(claim));

        assertThrows(MerchantClaimCodeExpiredException.class,
                () -> service.verify(claimant, claim.getId(), "123456"));

        assertThat(claim.getStatus()).isEqualTo(MerchantClaimStatus.REJECTED);
    }

    @Test
    void verify_notAwaitingCode_refused() {
        var claim = awaitingClaim("123456");
        claim.setStatus(MerchantClaimStatus.PENDING_REVIEW);
        when(claimRepository.findByIdAndUserId(claim.getId(), claimant.getId())).thenReturn(Optional.of(claim));

        assertThrows(MerchantClaimNotVerifiableException.class,
                () -> service.verify(claimant, claim.getId(), "123456"));
    }

    @Test
    void approveByAdmin_resolvedClaim_refused() {
        var claim = awaitingClaim("123456");
        claim.setStatus(MerchantClaimStatus.APPROVED);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertThrows(MerchantClaimAlreadyResolvedException.class,
                () -> service.approveByAdmin(claim.getId(), UUID.randomUUID()));
    }

    @Test
    void approveByAdmin_pendingClaim_promotesAndRecordsReviewer() {
        var adminId = UUID.randomUUID();
        var claim = awaitingClaim("123456");
        claim.setStatus(MerchantClaimStatus.PENDING_REVIEW);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        var response = service.approveByAdmin(claim.getId(), adminId);

        assertThat(response.status()).isEqualTo(MerchantClaimStatus.APPROVED);
        assertThat(claim.getReviewedByUserId()).isEqualTo(adminId);
        verify(merchantSubscriptionService).ensureForChain(CHAIN_ROOT);
    }
}
