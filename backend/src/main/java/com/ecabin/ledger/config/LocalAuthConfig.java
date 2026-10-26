package com.ecabin.ledger.config;

import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;

/** A per-process signing key for the loopback-only local demonstration profile. */
@Configuration
@Profile("local-auth")
public class LocalAuthConfig {
    public static final String ISSUER = "ecabin-ledger-local";
    public static final String AUDIENCE = "ecabin-ledger-api";

    @Bean
    ApplicationRunner rejectLocalAuthOutsideWorkstation(Environment environment) {
        return args -> {
            for (String profile : environment.getActiveProfiles()) {
                if (profile.equalsIgnoreCase("uat") || profile.equalsIgnoreCase("staging") || profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
                    throw new IllegalStateException("The local-auth profile cannot be enabled with a UAT, staging, or production profile.");
                }
            }
        };
    }

    @Bean
    SecretKey localJwtSigningKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder localJwtEncoder(SecretKey localJwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(localJwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey localJwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(localJwtSigningKey)
            .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(AUDIENCE)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required API audience is missing.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER), audienceValidator, new MaximumJwtLifetimeValidator()));
        return decoder;
    }
}
