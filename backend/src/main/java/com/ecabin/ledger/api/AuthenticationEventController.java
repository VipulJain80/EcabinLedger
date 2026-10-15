package com.ecabin.ledger.api;

import com.ecabin.ledger.config.LocalAuthConfig;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.UserLoginMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Records an explicit application logout. It does not revoke an enterprise identity-provider token. */
@RestController
public class AuthenticationEventController {
    private final CurrentUserResolver users;
    private final UserLoginMonitoringService monitoring;

    public AuthenticationEventController(CurrentUserResolver users, UserLoginMonitoringService monitoring) {
        this.users = users; this.monitoring = monitoring;
    }

    @Operation(summary = "Record explicit eCabin Ledger logout for the authenticated membership")
    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(JwtAuthenticationToken auth) {
        String source = auth.getToken().getIssuer() != null && LocalAuthConfig.ISSUER.equals(auth.getToken().getIssuer().toString()) ? "LOCAL_DEMO" : null;
        monitoring.recordExplicitLogout(users.require(auth), source);
    }
}
