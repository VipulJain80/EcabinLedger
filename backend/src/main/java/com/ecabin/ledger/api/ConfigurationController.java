package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** GET/POST-only tenant administration API. Every operation is organization-scoped and ADMIN-authorized. */
@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {
    public record DeactivateRequest(@NotBlank @Size(max = 1000) String reason) {}

    private final ConfigurationService configuration;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public ConfigurationController(ConfigurationService configuration, CurrentUserResolver users, PermissionService permissions) {
        this.configuration = configuration; this.users = users; this.permissions = permissions;
    }

    @GetMapping("/menu")
    public List<ConfigurationService.MenuItem> menu(JwtAuthenticationToken auth) {
        requireAdmin(auth);
        return configuration.menu();
    }

    @GetMapping("/{resource}")
    public List<Map<String, Object>> list(JwtAuthenticationToken auth, @PathVariable String resource,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        AuthenticatedUser user = requireAdmin(auth);
        return configuration.list(user.organizationId(), resource, includeInactive);
    }

    @GetMapping("/{resource}/table")
    public ConfigurationService.TablePage table(JwtAuthenticationToken auth, @PathVariable String resource,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String aircraftType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        AuthenticatedUser user = requireAdmin(auth);
        return configuration.table(user.organizationId(), resource, page, size, search, status, role, area, aircraftType, sortBy, sortDirection);
    }

    @GetMapping(value = "/{resource}/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<StreamingResponseBody> export(JwtAuthenticationToken auth, @PathVariable String resource,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String aircraftType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        AuthenticatedUser user = requireAdmin(auth);
        StreamingResponseBody body = output -> configuration.exportExcel(user.organizationId(), resource, search, status, role, area, aircraftType, sortBy, sortDirection, output);
        String filename = "ecabin-" + resource.replaceAll("[^a-zA-Z0-9-]", "") + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @PostMapping("/{resource}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> save(JwtAuthenticationToken auth, @PathVariable String resource,
            @Valid @RequestBody ConfigurationService.SaveRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        return configuration.save(user.organizationId(), user.externalUserId(), resource, request);
    }

    @PostMapping("/{resource}/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(JwtAuthenticationToken auth, @PathVariable String resource, @PathVariable String id,
            @Valid @RequestBody DeactivateRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        configuration.deactivate(user.organizationId(), user.externalUserId(), resource, id, request.reason());
    }

    @PostMapping("/{resource}/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(JwtAuthenticationToken auth, @PathVariable String resource, @PathVariable String id,
            @Valid @RequestBody DeactivateRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        configuration.activate(user.organizationId(), user.externalUserId(), resource, id, request.reason());
    }

    private AuthenticatedUser requireAdmin(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return user;
    }
}
