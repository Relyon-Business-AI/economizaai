package com.relyon.economizaai.service.analytics.meta;

import com.relyon.economizaai.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MetaConversionsServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Mock
    private MetaConversionsClient client;

    @InjectMocks
    private MetaConversionsService service;

    // Runs the dispatched task inline so we can assert the client was called.
    private final Executor executor = Runnable::run;

    @Test
    void doesNothingWhenNotConfigured() {
        var properties = new MetaConversionsProperties();
        service = new MetaConversionsService(client, executor, properties);

        service.reportCompleteRegistration(user(), "email");

        verifyNoInteractions(client);
    }

    @Test
    void dispatchesWhenConfigured() {
        var properties = configuredProperties();
        service = new MetaConversionsService(client, executor, properties);

        service.reportCompleteRegistration(user(), "email");

        verify(client).sendCompleteRegistration(
                eq(USER_ID + "-completeregistration"), eq("qa@economizaai.app"), any(),
                any(), any(), eq("email"), eq(true), anyLong());
    }

    @Test
    void ignoresBlankTokenConfig() {
        var properties = new MetaConversionsProperties();
        properties.setEnabled(true);
        properties.setPixelId("123");
        properties.setToken("  ");
        service = new MetaConversionsService(client, executor, properties);

        service.reportCompleteRegistration(user(), "google");

        verify(client, never()).sendCompleteRegistration(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyLong());
    }

    private MetaConversionsProperties configuredProperties() {
        var properties = new MetaConversionsProperties();
        properties.setEnabled(true);
        properties.setPixelId("1088687550381259");
        properties.setToken("capi-token");
        return properties;
    }

    private User user() {
        return User.builder()
                .id(USER_ID)
                .email("qa@economizaai.app")
                .build();
    }
}
