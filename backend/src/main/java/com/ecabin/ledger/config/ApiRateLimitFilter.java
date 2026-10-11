package com.ecabin.ledger.config;

import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ApiRateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Central API request gate. Runs after bearer authentication and before endpoint authorization. */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final ApiRateLimitService limits;
    private final CurrentUserResolver users;
    private final ObjectMapper json;
    private final int publicPerMinute;
    public ApiRateLimitFilter(ApiRateLimitService limits, CurrentUserResolver users, ObjectMapper json,
            @Value("${app.rate-limit.unauthenticated-per-minute:10}") int publicPerMinute) {
        this.limits=limits; this.users=users; this.json=json; this.publicPerMinute=publicPerMinute;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/v1/") && !"/api/dev/token".equals(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if ("/api/dev/token".equals(request.getRequestURI())) {
            try { if (!limits.evaluatePublic("LOCAL_DEV_TOKEN",directRemoteAddress(request),publicPerMinute)) { reject(response,60); return; } }
            catch (ResponseStatusException ex) { unavailable(response,ex); return; }
            chain.doFilter(request,response); return;
        }
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) { chain.doFilter(request,response); return; }
        final com.ecabin.ledger.security.AuthenticatedUser user;
        try { user=users.require(jwt); }
        catch (AccessDeniedException ex) { chain.doFilter(request,response); return; }
        final ApiRateLimitService.Evaluation evaluation;
        try { evaluation=limits.evaluate(user,limits.classify(request.getMethod(),request.getRequestURI())); }
        catch (ResponseStatusException ex) { unavailable(response,ex); return; }
        response.setHeader("RateLimit-Limit",Integer.toString(evaluation.limit()));
        response.setHeader("RateLimit-Remaining",Long.toString(Math.max(0,evaluation.limit()-evaluation.count())));
        response.setHeader("RateLimit-Reset",Long.toString(evaluation.retryAfterSeconds()));
        if(!evaluation.allowed()) { reject(response,evaluation.retryAfterSeconds()); return; }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,long retry) throws IOException {
        response.setStatus(429); response.setHeader("Retry-After",Long.toString(Math.max(1,retry))); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),Map.of("error",Map.of("code","rate_limit_exceeded","message","The request limit for this API category has been reached.")));
    }
    private void unavailable(HttpServletResponse response,ResponseStatusException ex) throws IOException {
        response.setStatus(ex.getStatusCode().value()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),Map.of("error",Map.of("code","rate_limit_unavailable","message","Rate-limit enforcement is temporarily unavailable.")));
    }
    private String directRemoteAddress(ServletRequest request) {
        ServletRequest connection=request;
        while(connection instanceof ServletRequestWrapper wrapper) connection=wrapper.getRequest();
        return connection.getRemoteAddr();
    }
}
