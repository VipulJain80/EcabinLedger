package com.ecabin.ledger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiCorsMethodsTest {
    @Test
    void corsAdvertisesOnlyReadAndPostMethodsPlusPreflight() {
        var source = new SecurityConfig().corsConfigurationSource("http://localhost:3000");
        var cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/workflow-statuses"));

        assertEquals(List.of("GET", "POST", "OPTIONS"), cors.getAllowedMethods());
    }
}
