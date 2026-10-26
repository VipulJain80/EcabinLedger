package com.ecabin.ledger.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Enforces a fixed maximum JWT lifetime independently of the issuer's configured exp claim. */
public final class MaximumJwtLifetimeValidator implements OAuth2TokenValidator<Jwt> {
    public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(30);
    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error("invalid_token", "Token lifetime is invalid or expired.", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant now = Instant.now();
        if (issuedAt == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt)
                || issuedAt.isAfter(now)
                || !now.isBefore(expiresAt)
                || expiresAt.isAfter(issuedAt.plus(MAXIMUM_LIFETIME))
                || !now.isBefore(issuedAt.plus(MAXIMUM_LIFETIME))) {
            return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
