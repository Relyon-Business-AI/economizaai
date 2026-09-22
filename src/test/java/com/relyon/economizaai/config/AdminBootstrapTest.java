package com.relyon.economizaai.config;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock private UserRepository userRepository;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email("a@b.com").role(role).build();
    }

    @Test
    void promotesConfiguredUserToAdmin() {
        var u = user(Role.USER);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));

        new AdminBootstrap(userRepository, " a@b.com ").promoteConfiguredAdmins();

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(Role.ADMIN, captor.getValue().getRole());
    }

    @Test
    void alreadyAdmin_isNotResaved() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user(Role.ADMIN)));

        new AdminBootstrap(userRepository, "a@b.com").promoteConfiguredAdmins();

        verify(userRepository, never()).save(any());
    }

    @Test
    void unknownEmail_isSkippedNotCreated() {
        when(userRepository.findByEmail("ghost@b.com")).thenReturn(Optional.empty());

        new AdminBootstrap(userRepository, "ghost@b.com").promoteConfiguredAdmins();

        verify(userRepository, never()).save(any());
    }

    @Test
    void emptyConfig_isNoOp() {
        new AdminBootstrap(userRepository, "").promoteConfiguredAdmins();
        verifyNoInteractions(userRepository);
    }

    @Test
    void multipleEmails_areEachResolved() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user(Role.USER)));
        when(userRepository.findByEmail("c@d.com")).thenReturn(Optional.of(user(Role.USER)));

        new AdminBootstrap(userRepository, "a@b.com, c@d.com").promoteConfiguredAdmins();

        verify(userRepository).findByEmail("a@b.com");
        verify(userRepository).findByEmail("c@d.com");
    }
}
