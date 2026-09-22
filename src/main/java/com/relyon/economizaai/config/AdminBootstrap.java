package com.relyon.economizaai.config;

import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Promotes configured accounts to {@link Role#ADMIN} on startup.
 *
 * <p>There's no in-app way to mint an admin (registration is always USER and
 * there's no promote endpoint), so admin access is bootstrapped from the
 * {@code ADMIN_EMAILS} env var (comma-separated). Idempotent — runs every boot,
 * only writes when the role actually changes. Survives DB rebuilds: re-promotes
 * the configured emails as soon as those accounts exist again.
 *
 * <p>The account must already exist (registered via the app). An unknown email
 * is logged and re-checked on the next boot — no account is created here.
 */
@Slf4j
@Component
public class AdminBootstrap {

    private final UserRepository userRepository;
    private final List<String> adminEmails;

    public AdminBootstrap(UserRepository userRepository,
                          @Value("${economizaai.admin.emails:}") String adminEmails) {
        this.userRepository = userRepository;
        // Match the email exactly as stored — registration persists it as-typed
        // (no normalization), and findByEmail is an exact lookup.
        this.adminEmails = Arrays.stream(adminEmails.split(","))
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .distinct()
                .toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteConfiguredAdmins() {
        if (adminEmails.isEmpty()) return;
        var promoted = 0;
        for (var email : adminEmails) {
            var user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                log.warn("admin.bootstrap.skip email={} reason=no_such_account (will retry next boot)", email);
                continue;
            }
            if (user.getRole() == Role.ADMIN) continue;
            user.setRole(Role.ADMIN);
            userRepository.save(user);
            promoted++;
            log.info("admin.bootstrap.promoted email={}", email);
        }
        log.info("admin.bootstrap done configured={} promoted={}", adminEmails.size(), promoted);
    }
}
