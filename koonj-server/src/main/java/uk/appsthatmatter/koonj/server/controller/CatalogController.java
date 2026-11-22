package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.connector.FileAttributes;
import uk.appsthatmatter.koonj.server.entity.StorageState;
import uk.appsthatmatter.koonj.server.service.CatalogScanService;
import uk.appsthatmatter.koonj.server.service.ConnectionService;
import uk.appsthatmatter.koonj.server.service.FileLineageService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogScanService catalog;

    private final ConnectionService connections;
    private final FileLineageService lineage;

    public CatalogController(CatalogScanService catalog, ConnectionService connections, FileLineageService lineage) {
        this.catalog = catalog;
        this.connections = connections;
        this.lineage = lineage;
    }

    @GetMapping("/locations")
    @Operation(summary = "List catalog locations", description = "Return the fields required to browse files and select transfer locations.")
    @PreAuthorize(KoonjRoles.READ)
    public List<CatalogLocationResponse> locations() { return connections.catalogLocations(); }

    @PostMapping("/scan/{connectionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Queue a catalog scan", description = "Discover physical entries and remove entries absent from the scanned location. Track completion through the returned job ID.")
    @PreAuthorize(KoonjRoles.OPERATE)
    public JobResponse scan(@PathVariable UUID connectionId) { return catalog.submit(connectionId); }

    @GetMapping("/files/{fileId}")
    @PreAuthorize(KoonjRoles.READ)
    public FileResponse get(@PathVariable UUID fileId) { return catalog.get(fileId); }

    @GetMapping("/files/{fileId}/attributes")
    @PreAuthorize(KoonjRoles.READ)
    public FileAttributes attributes(@PathVariable UUID fileId) { return catalog.attributes(fileId); }

    @GetMapping("/locations/{connectionId}/lineage")
    @PreAuthorize(KoonjRoles.READ)
    public PageResponse<FileMovementResponse> lineage(@PathVariable UUID connectionId, @RequestParam String path,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return lineage.forFile(connectionId, path, page, size);
    }

    @GetMapping(value = "/locations/{connectionId}/lineage/report", produces = "text/csv;charset=UTF-8")
    @PreAuthorize(KoonjRoles.READ)
    public ResponseEntity<StreamingResponseBody> lineageReport(@PathVariable UUID connectionId, @RequestParam String path) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"folder-checksum-report.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(lineage.folderReport(connectionId, path));
    }

    @GetMapping("/files")
    @Operation(summary = "Browse cataloged files", description = "Filter by physical connection, lifecycle state, and literal relative-path prefix.")
    @PreAuthorize(KoonjRoles.READ)
    public PageResponse<FileResponse> list(@RequestParam(required = false) UUID connectionId,
            @RequestParam(required = false) StorageState storageState, @RequestParam(required = false) String pathPrefix,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return catalog.list(connectionId, storageState, pathPrefix,
                PageRequest.of(page, size, Sort.by("relativePath", "id")));
    }
}
