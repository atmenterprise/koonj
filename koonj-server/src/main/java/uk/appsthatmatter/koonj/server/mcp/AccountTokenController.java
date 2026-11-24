package uk.appsthatmatter.koonj.server.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.security.ApiKeyService;
import uk.appsthatmatter.koonj.server.security.RolePermissions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account")
public class AccountTokenController {
    private final ApiKeyService keys;
    private final RolePermissions permissions;
    private final McpTools tools;

    public AccountTokenController(ApiKeyService keys, RolePermissions permissions, McpTools tools) {
        this.keys = keys;
        this.permissions = permissions;
        this.tools = tools;
    }

    public record TokenRequest(@NotBlank @Size(max = 120) String name, Instant expiresAt) {}

    @GetMapping("/tokens")
    public ResponseEntity<List<ApiKeyService.TokenView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.list());
    }

    @PostMapping("/tokens")
    public ResponseEntity<ApiKeyService.CreatedToken> create(Authentication authentication, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(keys.create(authentication, request.name(), request.expiresAt()));
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        keys.revoke(id);
    }

    @GetMapping("/mcp-tools")
    public List<McpTools.Availability> availability(Authentication authentication) {
        return tools.availability(permissions.effective(authentication));
    }
}
