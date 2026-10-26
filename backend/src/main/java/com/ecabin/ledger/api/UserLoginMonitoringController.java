package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.UserLoginMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/administration/login-monitoring")
public class UserLoginMonitoringController {
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final UserLoginMonitoringService monitoring;

    public UserLoginMonitoringController(CurrentUserResolver users, PermissionService permissions, UserLoginMonitoringService monitoring) {
        this.users = users; this.permissions = permissions; this.monitoring = monitoring;
    }

    @Operation(summary = "Get tenant-scoped user login summary", description = "Requires an active membership with ADMINISTER_ORGANIZATION.")
    @GetMapping("/summary")
    public UserLoginMonitoringService.Summary summary(JwtAuthenticationToken auth) {
        return monitoring.summary(admin(auth));
    }

    @Operation(summary = "Search tenant-scoped user login activity")
    @GetMapping("/users")
    public UserLoginMonitoringService.UserPage users(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "ALL") String activity,
            @RequestParam(defaultValue = "lastLoginAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        return monitoring.users(admin(auth), page, size, search, status, activity, sortBy, sortDirection);
    }

    @Operation(summary = "Export all matching tenant-scoped user login activity")
    @GetMapping(value = "/users/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportUsers(JwtAuthenticationToken auth,
            @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "ALL") String activity,
            @RequestParam(defaultValue = "lastLoginAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        AuthenticatedUser user = admin(auth);
        byte[] workbook;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            monitoring.exportUsers(user, search, status, activity, sortBy, sortDirection, columns, output);
            workbook = output.toByteArray();
        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not prepare login activity export."); }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename("ecabin-user-login-monitoring.xlsx", StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(workbook);
    }

    @Operation(summary = "Get paginated login/logout history for a tenant membership")
    @GetMapping("/users/{externalUserId}/events")
    public UserLoginMonitoringService.EventPage events(JwtAuthenticationToken auth, @PathVariable String externalUserId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return monitoring.events(admin(auth), externalUserId, page, size);
    }

    @Operation(summary = "Export all login/logout events for a tenant membership")
    @GetMapping(value = "/users/{externalUserId}/events/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportEvents(JwtAuthenticationToken auth, @PathVariable String externalUserId) {
        AuthenticatedUser user = admin(auth);
        byte[] workbook;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            monitoring.exportEvents(user, externalUserId, output);
            workbook = output.toByteArray();
        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not prepare login event export."); }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename("ecabin-login-event-history.xlsx", StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(workbook);
    }

    private AuthenticatedUser admin(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return user;
    }
}
