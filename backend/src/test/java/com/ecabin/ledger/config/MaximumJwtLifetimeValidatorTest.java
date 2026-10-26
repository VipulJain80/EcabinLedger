package com.ecabin.ledger.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MaximumJwtLifetimeValidatorTest {
    private final MaximumJwtLifetimeValidator validator = new MaximumJwtLifetimeValidator();

    @Test
    void acceptsAWellFormedTokenWithThirtyMinuteLifetime() {
        Instant issuedAt = Instant.now().minusSeconds(5);
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(1800))
            .build();

        assertFalse(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsTokensAdvertisedForLongerThanThirtyMinutes() {
        Instant issuedAt = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(1801))
            .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsTokensWithoutAnIssueTime() {
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsTokensAfterTheirMaximumAgeEvenWhenTheirExpClaimIsLater() {
        Instant issuedAt = Instant.now().minusSeconds(1801);
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(3600))
            .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsAtTheExactExpirationTimeWithoutClockSkew() {
        Instant issuedAt = Instant.now().minusSeconds(60);
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .issuedAt(issuedAt)
            .expiresAt(Instant.now().minusMillis(1))
            .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }
}
