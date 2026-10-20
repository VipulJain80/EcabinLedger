package com.ecabin.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Arrays;
import java.util.List;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionAuthorization;
import jakarta.servlet.DispatcherType;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> rateLimitFilterRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile("!local-auth")
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${app.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required API audience is missing.", null));
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:http://localhost:3000}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, PermissionAuthorization authorization, ApiRateLimitFilter rateLimitFilter) throws Exception {
        return http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401); response.setContentType("application/json");
                    response.getWriter().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Authentication is required.\"}}");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"error\":{\"code\":\"forbidden\",\"message\":\"You are not authorized to perform this operation.\"}}");
                }))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/dev/token").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/prometheus").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers("/actuator/**", "/api-docs", "/api-docs/**", "/swagger-ui", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/webjars/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/me", "/api/v1/navigation").access(authorization.authenticatedMember())
                // The summary endpoint is protected by DefectController's active
                // membership, defect-read, and record-scope checks. It is not a
                // report catalog or report-data API.
                .requestMatchers(HttpMethod.GET, "/api/v1/dashboard/summary").access(authorization.authenticatedMember())
                .requestMatchers(HttpMethod.GET, "/api/v1/reports", "/api/v1/reports/**").access(authorization.anyOf(Permission.REPORT_VIEW))
                // Service checks both permissions and the per-report execute grant; the request
                // gate accepts either permission so authenticated callers receive that precise check.
                .requestMatchers(HttpMethod.POST, "/api/v1/reports/*/query").access(authorization.anyOf(Permission.REPORT_VIEW, Permission.REPORT_EXECUTE))
                .requestMatchers(HttpMethod.POST, "/api/v1/reports/*/export").access(authorization.anyOf(Permission.REPORT_VIEW, Permission.REPORT_EXECUTE, Permission.REPORT_EXPORT))
                .requestMatchers(HttpMethod.GET, "/api/v1/report-templates").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.GET, "/api/v1/report-admin/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.POST, "/api/v1/report-admin/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.GET, "/api/v1/workflow-statuses", "/api/v1/workflow-statuses/**").access(authorization.anyOf(Permission.VIEW, Permission.DEFECT_VIEW_ALL, Permission.REPORT, Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN))
                .requestMatchers(HttpMethod.POST, "/api/v1/workflow-statuses/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.GET, "/api/v1/administration/login-monitoring/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").access(authorization.authenticatedMember())
                .requestMatchers(HttpMethod.GET, "/api/v1/rate-limits/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.POST, "/api/v1/rate-limits/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.GET, "/api/v1/maintenance-work-queue").access(authorization.anyOf(Permission.PERFORM_ACTION))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects/*/maintenance-sla-policy").access(authorization.anyOf(Permission.PERFORM_ACTION))
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications").access(authorization.anyOf(Permission.VIEW))
                .requestMatchers(HttpMethod.GET, "/api/v1/review-queue").access(authorization.anyOf(Permission.REVIEW, Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.GET, "/api/v1/reviewer-teams").access(authorization.anyOf(Permission.REVIEW))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects/*/review-decision-options").access(authorization.anyOf(Permission.REVIEW))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects/*/attachments").access(authorization.anyOf(Permission.REPORT, Permission.PERFORM_ACTION, Permission.REVIEW, Permission.APPROVE, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects/*/events").access(authorization.anyOf(Permission.AUDIT, Permission.PERFORM_ACTION, Permission.REVIEW, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects/*/operational-forms").access(authorization.authenticatedMember())
                .requestMatchers(HttpMethod.GET, "/api/v1/areas/*/forms").access(authorization.anyOf(Permission.VIEW, Permission.DEFECT_VIEW_ALL, Permission.REPORT, Permission.REVIEW))
                .requestMatchers(HttpMethod.GET, "/api/v1/reference-data/severity-badges").access(authorization.anyOf(Permission.VIEW,Permission.DEFECT_VIEW_ALL,Permission.REPORT,Permission.REVIEW,Permission.INSPECT,Permission.ASSIGN_ACTION,Permission.PERFORM_ACTION,Permission.VERIFY,Permission.APPROVE,Permission.CLOSE,Permission.REOPEN))
                .requestMatchers(HttpMethod.GET, "/api/v1/reference-data", "/api/v1/reference-data/**").access(authorization.anyOf(Permission.VIEW,Permission.REPORT,Permission.REVIEW))
                // Published form definitions/options and case submissions are needed by the
                // corresponding operational teams; controller/service checks still enforce
                // the exact form purpose, case scope, and workflow permission.
                .requestMatchers(HttpMethod.GET, "/api/v1/forms/**", "/api/v1/defects/*/form-submissions").access(authorization.anyOf(
                    Permission.VIEW, Permission.REPORT, Permission.REVIEW, Permission.INSPECT,
                    Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.REOPEN, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/aircraft/options").access(authorization.anyOf(Permission.REPORT))
                .requestMatchers(HttpMethod.GET, "/api/v1/aircraft/**").access(authorization.anyOf(Permission.VIEW, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/attachments/*/download").access(authorization.anyOf(Permission.VIEW, Permission.REPORT, Permission.PERFORM_ACTION, Permission.REVIEW, Permission.APPROVE, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/defects", "/api/v1/defects/*",
                    "/api/v1/fleet-status/**")
                    .access(authorization.anyOf(Permission.VIEW, Permission.DEFECT_VIEW_ALL))
                .requestMatchers(HttpMethod.GET, "/api/v1/configuration/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects").access(authorization.anyOf(Permission.REPORT))
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/**").access(authorization.anyOf(Permission.VIEW))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/transitions").access(authorization.anyOf(
                    Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION, Permission.PERFORM_ACTION,
                    Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/review-decision", "/api/v1/defects/*/review-routing").access(authorization.anyOf(Permission.REVIEW))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/inspections").access(authorization.anyOf(Permission.INSPECT))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/actions").access(authorization.anyOf(Permission.ASSIGN_ACTION))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/actions/*/reassign").access(authorization.anyOf(Permission.ASSIGN_ACTION))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/actions/*/start", "/api/v1/defects/*/actions/*/complete", "/api/v1/defects/*/actions/*/maintenance-form")
                    .access(authorization.anyOf(Permission.PERFORM_ACTION))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/verifications").access(authorization.anyOf(Permission.VERIFY))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/approvals").access(authorization.anyOf(Permission.APPROVE))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/form-submissions").access(authorization.anyOf(
                    Permission.REPORT, Permission.INSPECT, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.REOPEN))
                .requestMatchers(HttpMethod.POST, "/api/v1/defects/*/forms/*/versions/*/fields/*/attachments")
                    .access(authorization.anyOf(Permission.REPORT, Permission.INSPECT, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.REOPEN))
                .requestMatchers(HttpMethod.POST, "/api/v1/attachments/*/remove").access(authorization.anyOf(Permission.REPORT, Permission.INSPECT, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.REOPEN))
                .requestMatchers(HttpMethod.POST, "/api/v1/configuration/**").access(authorization.anyOf(Permission.ADMINISTER_ORGANIZATION))
                // All current API routes are explicitly classified above; new routes require an authorization rule.
                .requestMatchers("/api/v1/**").denyAll()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
            // Apply limits only after authentication and endpoint authorization, so
            // unauthorized calls neither consume tenant quota nor mask a 401/403.
            .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)
            .build();
    }
}
