package com.relyon.economizaai.service.photoattempt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PhotoRetentionServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<PhotoAttemptStorage> provider(PhotoAttemptStorage storage) {
        var provider = (ObjectProvider<PhotoAttemptStorage>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        return provider;
    }

    @Test
    void noStorageWired_isInactiveAndNoOps() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        var service = new PhotoRetentionService(provider(null), true);

        assertThat(service.isEnabled()).isTrue();   // toggle on...
        assertThat(service.isActive()).isFalse();   // ...but no backend -> inactive
        service.saveAttempt(UUID.randomUUID(), List.of(new byte[]{1}), List.of("image/jpeg"));
        verifyNoInteractions(storage);
    }

    @Test
    void disabledToggle_doesNotStoreEvenWithBackend() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        var service = new PhotoRetentionService(provider(storage), false);

        assertThat(service.isActive()).isFalse();
        service.saveAttempt(UUID.randomUUID(), List.of(new byte[]{1}), List.of("image/jpeg"));
        verify(storage, never()).store(anyString(), any(), any());
    }

    @Test
    void enabledWithBackend_storesAndLiveToggleFlips() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        var service = new PhotoRetentionService(provider(storage), true);
        var receiptId = UUID.randomUUID();

        assertThat(service.isActive()).isTrue();
        service.saveAttempt(receiptId, List.of(new byte[]{1, 2}), List.of("image/png"));
        verify(storage).store(eq(receiptId.toString()), any(), eq(List.of("image/png")));

        service.setEnabled(false, "admin@economizaai.app");
        assertThat(service.isActive()).isFalse();
    }

    @Test
    void saveAttempt_swallowsStorageErrors() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        when(storage.store(anyString(), any(), any())).thenThrow(new RuntimeException("R2 down"));
        var service = new PhotoRetentionService(provider(storage), true);

        // não lança — retenção é best-effort e nunca pode derrubar a ingestão
        service.saveAttempt(UUID.randomUUID(), List.of(new byte[]{1}), List.of("image/jpeg"));
    }

    @Test
    void deleteAttempt_removesEvenWithTheToggleOff() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        var service = new PhotoRetentionService(provider(storage), false);
        var receiptId = UUID.randomUUID();

        assertThat(service.deleteAttempt(receiptId)).isTrue();
        verify(storage).delete(receiptId.toString());
    }

    @Test
    void deleteAttempt_swallowsStorageErrors() throws Exception {
        var storage = mock(PhotoAttemptStorage.class);
        doThrow(new IOException("R2 down")).when(storage).delete(anyString());
        var service = new PhotoRetentionService(provider(storage), true);

        assertThat(service.deleteAttempt(UUID.randomUUID())).isFalse();
    }

    @Test
    void deleteAttempt_withoutBackendIsANoOp() {
        var service = new PhotoRetentionService(provider(null), true);

        assertThat(service.deleteAttempt(UUID.randomUUID())).isTrue();
    }
}
