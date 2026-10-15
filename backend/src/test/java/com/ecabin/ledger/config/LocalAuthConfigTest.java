package com.ecabin.ledger.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class LocalAuthConfigTest {
    private final LocalAuthConfig config = new LocalAuthConfig();

    @Test
    void permitsLocalAuthForWorkstationProfiles() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-auth", "dev");
        var runner = config.rejectLocalAuthOutsideWorkstation(environment);
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsLocalAuthWithDeployedProfiles() {
        for (String deployed : new String[] {"uat", "staging", "prod", "production"}) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles("local-auth", deployed);
            var runner = config.rejectLocalAuthOutsideWorkstation(environment);
            assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()), deployed);
        }
    }
}
