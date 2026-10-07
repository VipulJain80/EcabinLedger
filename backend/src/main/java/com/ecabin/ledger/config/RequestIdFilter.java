package com.ecabin.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = validatedUuid(request.getHeader("X-Request-Id"));
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try { chain.doFilter(request, response); }
        finally { MDC.remove("requestId"); }
    }

    private String validatedUuid(String candidate) {
        try { return UUID.fromString(candidate).toString(); }
        catch (RuntimeException exception) { return UUID.randomUUID().toString(); }
    }
}
