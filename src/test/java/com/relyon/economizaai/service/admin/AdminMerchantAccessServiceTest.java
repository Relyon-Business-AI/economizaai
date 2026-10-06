package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.MerchantAccessAlreadyExistsException;
import com.relyon.economizaai.exception.MerchantAccessNotFoundException;
import com.relyon.economizaai.exception.MerchantRoleRequiredException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMerchantAccessServiceTest {

    private static final String CHAIN_ROOT = "93015006";

    @Mock private UserRepository userRepository;
    @Mock private MerchantAccessRepository merchantAccessRepository;

    @InjectMocks private AdminMerchantAccessService service;

    private User merchantUser() {
        return User.builder().id(UUID.randomUUID()).name("Mercado Teste")
                .email("merchant@economizaai.app").role(Role.MERCHANT).build();
    }

    @Test
    void grant_merchantUser_savesAndReturnsGrant() {
        var user = merchantUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(merchantAccessRepository.existsByUserIdAndCnpjRoot(user.getId(), CHAIN_ROOT)).thenReturn(false);
        when(merchantAccessRepository.save(any(MerchantAccess.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var granted = service.grant(user.getId(), CHAIN_ROOT);

        assertThat(granted.cnpjRoot()).isEqualTo(CHAIN_ROOT);
        verify(merchantAccessRepository).save(any(MerchantAccess.class));
    }

    @Test
    void grant_nonMerchantUser_refused() {
        var regular = User.builder().id(UUID.randomUUID()).name("Comum")
                .email("user@test.com").role(Role.USER).build();
        when(userRepository.findById(regular.getId())).thenReturn(Optional.of(regular));

        assertThrows(MerchantRoleRequiredException.class, () -> service.grant(regular.getId(), CHAIN_ROOT));
        verify(merchantAccessRepository, never()).save(any());
    }

    @Test
    void grant_duplicateChain_refused() {
        var user = merchantUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(merchantAccessRepository.existsByUserIdAndCnpjRoot(user.getId(), CHAIN_ROOT)).thenReturn(true);

        assertThrows(MerchantAccessAlreadyExistsException.class, () -> service.grant(user.getId(), CHAIN_ROOT));
        verify(merchantAccessRepository, never()).save(any());
    }

    @Test
    void grant_unknownUser_throwsNotFound() {
        var unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.grant(unknownId, CHAIN_ROOT));
    }

    @Test
    void revoke_existingGrant_deletes() {
        var user = merchantUser();
        var access = MerchantAccess.builder().user(user).cnpjRoot(CHAIN_ROOT).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(merchantAccessRepository.findByUserIdAndCnpjRoot(user.getId(), CHAIN_ROOT))
                .thenReturn(Optional.of(access));

        service.revoke(user.getId(), CHAIN_ROOT);

        verify(merchantAccessRepository).delete(access);
    }

    @Test
    void revoke_missingGrant_throwsNotFound() {
        var user = merchantUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(merchantAccessRepository.findByUserIdAndCnpjRoot(user.getId(), CHAIN_ROOT))
                .thenReturn(Optional.empty());

        assertThrows(MerchantAccessNotFoundException.class, () -> service.revoke(user.getId(), CHAIN_ROOT));
    }

    @Test
    void list_mapsGrants() {
        var user = merchantUser();
        var access = MerchantAccess.builder().user(user).cnpjRoot(CHAIN_ROOT).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(merchantAccessRepository.findAllByUserId(user.getId())).thenReturn(List.of(access));

        var grants = service.list(user.getId());

        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).cnpjRoot()).isEqualTo(CHAIN_ROOT);
    }
}
