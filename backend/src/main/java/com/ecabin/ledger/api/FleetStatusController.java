package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ApiModels.FleetStatusPage;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.DefectService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Read-only fleet summary built from the authenticated tenant's aircraft and defect records. */
@RestController
@RequestMapping("/api/v1/fleet-status")
public class FleetStatusController {
    private record ReadContext(AuthenticatedUser user) {}
    private final DefectService defects;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public FleetStatusController(DefectService defects, CurrentUserResolver users, PermissionService permissions) {
        this.defects = defects; this.users = users; this.permissions = permissions;
    }

    @GetMapping("/table")
    public FleetStatusPage table(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String fleetId,
            @RequestParam(required = false) String aircraftType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        ReadContext context = requireView(auth);
        AuthenticatedUser user = context.user();
        // Fleet Status is a shared organization overview. Keep tenant isolation and
        // requireView above, while showing the same fleet/defect aggregates to each role.
        return defects.fleetStatus(user.organizationId(), user.externalUserId(), false,
            false, false, fleetId, aircraftType, page, size, search, sortBy, sortDirection);
    }

    @GetMapping("/summary")
    public com.ecabin.ledger.api.ApiModels.FleetDefectSummary summary(JwtAuthenticationToken auth) {
        ReadContext context = requireView(auth);
        AuthenticatedUser user = context.user();
        return defects.fleetDefectSummary(user.organizationId(), user.externalUserId(), false,
            false, false);
    }

    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<StreamingResponseBody> export(JwtAuthenticationToken auth,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String fleetId,
            @RequestParam(required = false) String aircraftType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestParam(required = false) List<String> columns) {
        ReadContext context = requireView(auth);
        AuthenticatedUser user = context.user();
        UUID operatorId = user.organizationId();
        StreamingResponseBody body = output -> defects.exportFleetStatus(operatorId, user.externalUserId(), false, false, false, fleetId, aircraftType, search, sortBy, sortDirection, columns, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("ecabin-fleet-status.xlsx", StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private ReadContext requireView(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        var grants = permissions.requireDefectRead(user);
        return new ReadContext(user);
    }
}
