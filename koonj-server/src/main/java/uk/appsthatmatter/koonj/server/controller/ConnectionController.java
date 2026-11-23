package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import uk.appsthatmatter.koonj.server.security.PermissionRules;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.service.ConnectionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {
    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) { this.connections = connections; }

    @PostMapping({"", "/"})
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a storage connection", description = "Save provider, role, and access settings for a mounted root, SMB share, or S3 bucket.")
    @PreAuthorize(PermissionRules.STORAGE_MANAGE)
    public ConnectionResponse create(@Valid @RequestBody ConnectionRequest request) {
        return connections.create(request);
    }

    @GetMapping({"", "/"})
    @Operation(summary = "List storage connections", description = "Return configured connections in name order with credential values masked.")
    @PreAuthorize(PermissionRules.STORAGE_MANAGE)
    public List<ConnectionResponse> list() { return connections.list(); }

    @GetMapping("/locations")
    @PreAuthorize(PermissionRules.STORAGE_VIEW)
    public List<StorageLocationStatus> statusLocations() { return connections.statusLocations(); }

    @GetMapping("/usage")
    @PreAuthorize(PermissionRules.STORAGE_VIEW)
    @Operation(summary = "Summarize cataloged storage usage across connections")
    public StorageUsage usage() { return connections.storageUsage(); }

    @PutMapping("/{id}")
    @Operation(summary = "Update a storage connection", description = "Replace endpoint settings. Omitted or masked credentials retain their existing values.")
    @PreAuthorize(PermissionRules.STORAGE_MANAGE)
    public ConnectionResponse update(@PathVariable UUID id, @Valid @RequestBody ConnectionRequest request) {
        return connections.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a storage connection", description = "Delete a connection that has no catalog references or active jobs.")
    @PreAuthorize(PermissionRules.STORAGE_MANAGE)
    public void delete(@PathVariable UUID id) { connections.delete(id); }

    @GetMapping("/{id}/status")
    @Operation(summary = "Check storage connectivity", description = "Check listing access for an active connection using a read-only probe.")
    @PreAuthorize(PermissionRules.STORAGE_VIEW)
    public ResponseEntity<ConnectionHealth> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connections.connectivity(id));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Probe storage access", description = "Check listing access and, for writable roles, create and remove a temporary probe.")
    @PreAuthorize(PermissionRules.STORAGE_MANAGE)
    public ConnectionHealth test(@PathVariable UUID id) { return connections.testConnection(id); }
}
