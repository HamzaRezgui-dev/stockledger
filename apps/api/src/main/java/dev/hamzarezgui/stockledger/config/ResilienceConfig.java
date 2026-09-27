package dev.hamzarezgui.stockledger.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Turns on Spring Framework 7's built-in method retry.
 *
 * <p>Note there is no {@code spring-retry} dependency: as of Framework 7
 * (Spring Boot 4), {@code @Retryable} lives in
 * {@code org.springframework.resilience.annotation} in spring-context. The
 * separate project is no longer needed, and the attribute is {@code maxRetries}
 * rather than the old {@code maxAttempts}.
 *
 * <p>Without {@code @EnableResilientMethods} the {@code @Retryable} annotations
 * on {@link dev.hamzarezgui.stockledger.ledger.LedgerFacade} are silently inert
 * — the code would look correct and retry nothing, which is the failure mode
 * this whole arrangement is designed to avoid. It is covered by a test.
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {

    /**
     * A {@link Clock} bean so services never call {@code Instant.now()} directly.
     *
     * <p>Every timestamp the ledger records comes from here, which means a test
     * can freeze or advance time and assert on {@code occurredAt} and
     * {@code recordedAt} deterministically. Reaching for the static clock inside
     * a service is what makes time-dependent behaviour untestable.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
