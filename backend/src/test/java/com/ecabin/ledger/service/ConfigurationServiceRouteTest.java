package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecabin.ledger.security.Permission;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationServiceRouteTest {
    @Test
    void navigationAllowListIncludesProvisionedApplicationPagesAndRejectsUnknownRoutes() {
        assertTrue(ConfigurationService.isSupportedNavigationRoute("api-rate-limits"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("user-login-monitoring"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("report-defect"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("report-master"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("email-notifications"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("areas"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("area-team-mapping"));
        assertTrue(ConfigurationService.isSupportedNavigationRoute("home"));
        assertFalse(ConfigurationService.isSupportedNavigationRoute("https://example.invalid"));
    }

    @Test
    void defectViewAllNavigationDoesNotExposeUnrelatedViewOnlySections() {
        assertTrue(ConfigurationService.isDefectViewAllNavigationRoute("home"));
        assertTrue(ConfigurationService.isDefectViewAllNavigationRoute("operations"));
        assertTrue(ConfigurationService.isDefectViewAllNavigationRoute("defects"));
        assertFalse(ConfigurationService.isDefectViewAllNavigationRoute("reports"));
        assertFalse(ConfigurationService.isDefectViewAllNavigationRoute("fleet"));
        assertFalse(ConfigurationService.isDefectViewAllNavigationRoute("configuration"));
    }

    @Test
    void organizationAdministratorCanOpenReportsWithoutReportDataPermissions() {
        assertTrue(ConfigurationService.canSeeReports(Set.of(Permission.ADMINISTER_ORGANIZATION)));
        assertTrue(ConfigurationService.canSeeReports(Set.of(Permission.REPORT_VIEW)));
        assertFalse(ConfigurationService.canSeeReports(Set.of(Permission.VIEW)));
    }
}
