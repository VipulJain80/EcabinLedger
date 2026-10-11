package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ApiRateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rate-limits")
public class ApiRateLimitController {
    public record PolicyRequest(@NotBlank String routeKey,@NotBlank @Size(max=120) String name,@Size(max=500) String description,
        @NotBlank String scopeType,@NotNull @Min(1) @Max(10000) Integer maxRequests,@NotNull @Min(1) @Max(3600) Integer windowSeconds,
        @NotBlank @Size(max=1000) String reason) {}
    public record ChangeRequest(@NotBlank @Size(max=1000) String reason) {}
    private final ApiRateLimitService service; private final CurrentUserResolver users;
    public ApiRateLimitController(ApiRateLimitService service,CurrentUserResolver users){this.service=service;this.users=users;}
    @GetMapping("/catalog") public List<Map<String,Object>> catalog(JwtAuthenticationToken auth){return service.catalog(user(auth));}
    @GetMapping("/policies") public ApiRateLimitService.Page<ApiRateLimitService.Policy> list(JwtAuthenticationToken auth,
        @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="10") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue="") @Size(max=120) String search,@RequestParam(required=false) String routeKey,@RequestParam(defaultValue="ALL") String status){return service.list(user(auth),page,size,search,routeKey,status);}
    @GetMapping("/policies/{id}") public ApiRateLimitService.Policy get(JwtAuthenticationToken auth,@PathVariable UUID id){return service.get(user(auth),id);}
    @PostMapping("/policies") public ApiRateLimitService.Policy create(JwtAuthenticationToken auth,@Valid @RequestBody PolicyRequest body){return service.create(user(auth),toInput(body),MDC.get("requestId"));}
    @PostMapping("/policies/{id}") public ApiRateLimitService.Policy update(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody PolicyRequest body){return service.update(user(auth),id,toInput(body),MDC.get("requestId"));}
    @PostMapping("/policies/{id}/activate") public ApiRateLimitService.Policy activate(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody ChangeRequest body){return service.setActive(user(auth),id,true,body.reason(),MDC.get("requestId"));}
    @PostMapping("/policies/{id}/deactivate") public ApiRateLimitService.Policy deactivate(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody ChangeRequest body){return service.setActive(user(auth),id,false,body.reason(),MDC.get("requestId"));}
    @GetMapping("/effective") public List<Map<String,Object>> effective(JwtAuthenticationToken auth){return service.effective(user(auth));}
    @GetMapping("/usage") public Map<String,Object> usage(JwtAuthenticationToken auth){return service.usage(user(auth));}
    @GetMapping("/history") public ApiRateLimitService.Page<Map<String,Object>> history(JwtAuthenticationToken auth,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="10") @Min(1) @Max(100) int size){return service.history(user(auth),page,size);}
    private AuthenticatedUser user(JwtAuthenticationToken auth){return users.require(auth);}
    private ApiRateLimitService.PolicyInput toInput(PolicyRequest r){return new ApiRateLimitService.PolicyInput(r.routeKey(),r.name(),r.description(),r.scopeType(),r.maxRequests(),r.windowSeconds(),r.reason());}
}
