package com.relyon.economizaai;

import com.relyon.economizaai.config.NotificationDefaultsBackfill;
import com.relyon.economizaai.service.NormalizationBackfillRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Render dev service runs with the extra "lowmem" profile (lazy beans, no startup
 * backfills). Lazy init hides wiring errors until first use, so prove the whole context
 * still starts — and that every bean still resolves — under that profile.
 */
@SpringBootTest
@ActiveProfiles({"test", "lowmem"})
class LowMemProfileContextTest {

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;

    @Test
    void contextStartsLazyWithoutStartupBackfills() {
        assertEquals("true", environment.getProperty("spring.main.lazy-initialization"));
        assertTrue(context.getBeanNamesForType(NotificationDefaultsBackfill.class).length == 0);
        assertTrue(context.getBeanNamesForType(NormalizationBackfillRunner.class).length == 0);
    }

    @Test
    void everyLazyBeanStillResolves() {
        for (var beanName : context.getBeanDefinitionNames()) {
            context.getBean(beanName);
        }
    }
}
