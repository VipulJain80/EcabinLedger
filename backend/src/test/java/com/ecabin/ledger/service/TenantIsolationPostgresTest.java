package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.ApiModels.CreateDefectRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://gateway.invalid/",
    "app.security.audience=ecabin-test-api"
})
class TenantIsolationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefectService defects;
    UUID organizationA, organizationB;

    @BeforeEach
    void createTwoTenants() {
        organizationA = createTenant("A");
        organizationB = createTenant("B");
    }

    @Test
    void organizationCannotReadAnotherOrganizationsDefectsOrAudit() {
        UUID areaId = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'", UUID.class, organizationA);
        UUID componentId = jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'", UUID.class, organizationA);
        UUID conditionId = UUID.randomUUID();
        jdbc.update("INSERT INTO defect_conditions_mst(id,operator_id,code,name) VALUES(?,?,?,'Torn')", conditionId, organizationA, "TORN_"+conditionId.toString().substring(0,8));
        jdbc.update("INSERT INTO component_conditions_mst(operator_id,component_id,condition_id) VALUES(?,?,?)", organizationA, componentId, conditionId);
        var request = new CreateDefectRequest("N482NW", "CABIN", "SEAT", areaId, componentId, conditionId, "FWD CABIN ROW 14", 14, "14A", null, "Torn seat cover", "Cover torn along seam", "LOW");
        var created = defects.create(organizationA, "crew-001", request);

        assertEquals(0, defects.list(organizationB, null, null, null, 25).items().size());
        assertThrows(ResponseStatusException.class, () -> defects.get(organizationB, created.id()));
        assertThrows(ResponseStatusException.class, () -> defects.events(organizationB, created.id()));
        assertEquals(1, defects.list(organizationA, null, null, null, 25).items().size());
    }

    private UUID createTenant(String suffix) {
        UUID operator=UUID.randomUUID(), fleet=UUID.randomUUID(), aircraft=UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", operator, "DEMO " + suffix);
        jdbc.update("INSERT INTO fleets_mst(id,operator_id,name) VALUES(?,?,?)", fleet, operator, "Fleet " + suffix);
        jdbc.update("INSERT INTO aircraft_mst(id,operator_id,fleet_id,tail_number,aircraft_type) VALUES(?,?,?,?,?)", aircraft, operator, fleet, "N" + suffix + UUID.randomUUID().toString().substring(0,5), "A320-200");
        return operator;
    }
}
