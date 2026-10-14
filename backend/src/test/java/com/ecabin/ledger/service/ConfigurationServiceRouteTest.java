package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigurationServiceRouteTest {
    @Test
    void navigationAllowListIncludesProvisionedApplicationPagesAndRejectsUnknownRoutes() {
        assertTrue(ConfigurationService.isSupportedNavigationRoute("api-rate-limits"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("report-defect"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("areas"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("area-team-mapping"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("home"));
        assertFalse(ConfigurationService.isSupportedNavigationRoute("https://example.invalid"));
    }
}
