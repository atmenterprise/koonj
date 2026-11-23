package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataResponse;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest;
import uk.appsthatmatter.koonj.server.security.PermissionRules;
import uk.appsthatmatter.koonj.server.service.CatalogMetadataService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/locations/{connectionId}/metadata")
public class CatalogMetadataController {
    private final CatalogMetadataService metadata;

    public CatalogMetadataController(CatalogMetadataService metadata) {
        this.metadata = metadata;
    }

    @GetMapping
    @PreAuthorize(PermissionRules.CATALOG_VIEW)
    @Operation(summary = "Read catalog metadata", description = "List direct file tags, folder rules, and custom properties for a catalog location.")
    public List<CatalogMetadataResponse> list(@PathVariable UUID connectionId) {
        return metadata.list(connectionId);
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionRules.CATALOG_OPERATE)
    @Operation(summary = "Save catalog metadata", description = "Replace or merge direct metadata for selected files and folders in one transaction.")
    public void save(@PathVariable UUID connectionId, @Valid @RequestBody CatalogMetadataWriteRequest request) {
        metadata.save(connectionId, request);
    }
}
