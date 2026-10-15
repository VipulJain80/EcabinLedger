package com.ecabin.ledger.api;

import com.ecabin.ledger.config.LocalAuthConfig;
import com.ecabin.ledger.service.UserLoginMonitoringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only token issuer. This controller is absent unless the loopback-only local-auth profile is active. */
@RestController
@Profile("local-auth")
public class LocalDevAuthController {
    private static final UUID DEMO_OPERATOR_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final long TOKEN_LIFETIME_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final UserLoginMonitoringService loginMonitoring;
    private final List<String> allowedEmails;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalDevAuthController(JdbcTemplate jdbc, JwtEncoder encoder, UserLoginMonitoringService loginMonitoring,
            @Value("${app.local-auth.emails:abc@gmail.com}") String allowedEmails) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.loginMonitoring = loginMonitoring;
        this.allowedEmails = List.of(allowedEmails.split(",")).stream()
            .map(email -> email.trim().toLowerCase(Locale.ROOT))
            .filter(email -> !email.isBlank()).distinct().toList();
    }

    @PostMapping("/api/dev/token")
    public TokenResponse issueToken(@Valid @RequestBody TokenRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!allowedEmails.contains(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This email is not enabled for local demo access.");
        }
        boolean hasMembership = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=? AND isactive=1)",
            Boolean.class, DEMO_OPERATOR_ID, email));
        if (!hasMembership) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Local demo membership is missing. Apply backend/src/main/resources/db/dev-seed.sql first.");
        }

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(LocalAuthConfig.ISSUER)
            .audience(List.of(LocalAuthConfig.AUDIENCE))
            .subject(email)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("operator_id", DEMO_OPERATOR_ID.toString())
            .claim("email", email)
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        if (loginMonitoring != null) loginMonitoring.recordSuccessfulLogin(DEMO_OPERATOR_ID, email, UserLoginMonitoringService.LoginSource.LOCAL_DEMO);
        return new TokenResponse(token, expiresAt);
    }

    /** Compatibility constructor for controller unit tests that do not exercise event persistence. */
    public LocalDevAuthController(JdbcTemplate jdbc, JwtEncoder encoder, String allowedEmails) {
        this(jdbc, encoder, null, allowedEmails);
    }

    public record TokenRequest(@NotBlank @Email String email) {}
    public record TokenResponse(String accessToken, Instant expiresAt) {}
}
