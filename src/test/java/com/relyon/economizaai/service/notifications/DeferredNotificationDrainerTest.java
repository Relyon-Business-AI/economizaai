package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeferredNotificationDrainerTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @Mock private QuietHoursPolicy quietHours;

    private DeferredNotificationDrainer drainer;

    @BeforeEach
    void setUp() {
        drainer = new DeferredNotificationDrainer(notificationRepository, notificationService, quietHours,
                Runnable::run);
        lenient().when(quietHours.getMaxDeferredPerUser()).thenReturn(3);
        lenient().when(notificationRepository.claimDeferredDispatch(any())).thenReturn(1);
    }

    private Notification heldBack(User user) {
        return Notification.builder().id(UUID.randomUUID()).user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.PUSH)
                .title("t").body("b").dispatchDeferred(true).build();
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("drain@economizaai.app").build();
    }

    /** First page returns the given users; the next page is empty (they were drained). */
    private void pendingUsers(User... users) {
        var ids = new ArrayList<UUID>();
        for (var pendingUser : users) ids.add(pendingUser.getId());
        when(notificationRepository.findUserIdsWithDeferredDispatch(any()))
                .thenReturn(ids).thenReturn(List.of());
    }

    @Test
    void whileTheWindowIsOpen_nothingIsDrained() {
        when(quietHours.isQuietNow()).thenReturn(true);

        drainer.drainWhenWindowIsOver();

        verify(notificationRepository, never()).findUserIdsWithDeferredDispatch(any());
        verify(notificationService, never()).dispatchHeldBack(any());
    }

    @Test
    void whenTheWindowEnds_sendsTheHeldBackNotificationsOldestFirst() {
        when(quietHours.isQuietNow()).thenReturn(false);
        var user = user();
        var first = heldBack(user);
        var second = heldBack(user);
        pendingUsers(user);
        when(notificationRepository.findDeferredByUserId(user.getId())).thenReturn(List.of(first, second));

        drainer.drainWhenWindowIsOver();

        InOrder order = inOrder(notificationService);
        order.verify(notificationService).dispatchHeldBack(first);
        order.verify(notificationService).dispatchHeldBack(second);
        verify(notificationService, never()).keepInboxOnly(any(), any());
    }

    @Test
    void capsThePushesPerUser_theRestStayInboxOnly() {
        var user = user();
        var rows = List.of(heldBack(user), heldBack(user), heldBack(user), heldBack(user), heldBack(user));
        pendingUsers(user);
        when(notificationRepository.findDeferredByUserId(user.getId())).thenReturn(rows);

        drainer.drain();

        verify(notificationService).dispatchHeldBack(rows.get(0));
        verify(notificationService).dispatchHeldBack(rows.get(1));
        verify(notificationService).dispatchHeldBack(rows.get(2));
        verify(notificationService, never()).dispatchHeldBack(rows.get(3));
        verify(notificationService, never()).dispatchHeldBack(rows.get(4));
        verify(notificationService).keepInboxOnly(rows.get(3), DeferredNotificationDrainer.REASON_OVER_CAP);
        verify(notificationService).keepInboxOnly(rows.get(4), DeferredNotificationDrainer.REASON_OVER_CAP);
    }

    @Test
    void aRowClaimedByAnotherRunIsNotSentAgain() {
        var user = user();
        var takenElsewhere = heldBack(user);
        var mine = heldBack(user);
        pendingUsers(user);
        when(notificationRepository.findDeferredByUserId(user.getId())).thenReturn(List.of(takenElsewhere, mine));
        when(notificationRepository.claimDeferredDispatch(takenElsewhere.getId())).thenReturn(0);

        drainer.drain();

        verify(notificationService, never()).dispatchHeldBack(takenElsewhere);
        verify(notificationService).dispatchHeldBack(mine);
    }

    @Test
    void oneUsersFailureDoesNotStopTheOthers() {
        var broken = user();
        var healthy = user();
        var healthyRow = heldBack(healthy);
        pendingUsers(broken, healthy);
        when(notificationRepository.findDeferredByUserId(broken.getId()))
                .thenThrow(new IllegalStateException("db hiccup"));
        when(notificationRepository.findDeferredByUserId(healthy.getId())).thenReturn(List.of(healthyRow));

        assertDoesNotThrow(() -> drainer.drain());

        verify(notificationService).dispatchHeldBack(healthyRow);
    }

    @Test
    void aFailedSendDoesNotStopTheUsersOtherRows() {
        var user = user();
        var failing = heldBack(user);
        var next = heldBack(user);
        pendingUsers(user);
        when(notificationRepository.findDeferredByUserId(user.getId())).thenReturn(List.of(failing, next));
        doThrow(new IllegalStateException("expo down")).when(notificationService).dispatchHeldBack(failing);

        drainer.drain();

        verify(notificationService).dispatchHeldBack(next);
    }

    @Test
    void usersThatKeepFailingDoNotLoopForever() {
        var stuck = user();
        when(notificationRepository.findUserIdsWithDeferredDispatch(any())).thenReturn(List.of(stuck.getId()));
        when(notificationRepository.findDeferredByUserId(stuck.getId()))
                .thenThrow(new IllegalStateException("db hiccup"));

        assertDoesNotThrow(() -> drainer.drain());

        verify(notificationRepository).findDeferredByUserId(eq(stuck.getId()));
    }

    @Test
    void poolRejectionLeavesTheRowsForTheNextRun() {
        when(quietHours.isQuietNow()).thenReturn(false);
        var rejecting = new DeferredNotificationDrainer(notificationRepository, notificationService, quietHours,
                task -> {
                    throw new RejectedExecutionException("queue full");
                });

        assertDoesNotThrow(rejecting::drainWhenWindowIsOver);

        verify(notificationRepository, never()).findUserIdsWithDeferredDispatch(any());
        verifyNoInteractions(notificationService);
    }
}
