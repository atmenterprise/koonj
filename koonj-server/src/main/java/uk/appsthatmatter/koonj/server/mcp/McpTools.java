package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.security.Permission;
import uk.appsthatmatter.koonj.server.security.RolePermissions;
import uk.appsthatmatter.koonj.server.service.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static uk.appsthatmatter.koonj.server.mcp.McpArguments.Field.*;
import static uk.appsthatmatter.koonj.server.security.Permission.*;

@Component
public class McpTools {
    private static final McpArguments.Field PAGE = integer("page", 0, 100_000, 0);
    private static final McpArguments.Field LIMIT = integer("limit", 1, 50, 20);
    private static final McpArguments.Field CONNECTION = uuid("connectionId", true);
    private static final McpArguments.Field JOB = uuid("jobId", true);
    private static final McpArguments.Field FILE = uuid("fileId", true);
    private static final McpArguments.Field SOURCE = uuid("sourceConnectionId", true);
    private static final McpArguments.Field TARGET = uuid("targetConnectionId", true);
    private static final McpArguments.Field PATHS = array(text("paths", false, 2048));
    private static final McpArguments.Field FILES = array(uuid("fileIds", false));

    private final ConnectionService connections;
    private final CatalogScanService catalog;
    private final CatalogMetadataService metadata;
    private final FileLineageService lineage;
    private final FileTransferService transfers;
    private final JobService jobs;
    private final TemperaturePolicyService policy;
    private final RolePermissions permissions;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    private enum Temperature { HOT, WARM, COLD }

    private interface Handler {
        Object call(McpArguments arguments, Set<Permission> grants);
    }

    private record Tool(String name, String description, Permission permission, List<McpArguments.Field> fields, Handler handler) {
        Definition definition() { return new Definition(name, description, McpArguments.schema(fields)); }
    }

    public record Definition(String name, String description, Map<String, Object> inputSchema) {}
    public record Availability(String name, String description, Permission permission, boolean available) {}

    static final class PermissionDenied extends RuntimeException {
        PermissionDenied(Permission permission) {
            super("Permission denied: Required permission [" + permission + "] missing.");
        }
    }

    public McpTools(ConnectionService connections, CatalogScanService catalog, CatalogMetadataService metadata,
                    FileLineageService lineage, FileTransferService transfers, JobService jobs,
                    TemperaturePolicyService policy, RolePermissions permissions, HealthEndpoint health) {
        this.connections = connections;
        this.catalog = catalog;
        this.metadata = metadata;
        this.lineage = lineage;
        this.transfers = transfers;
        this.jobs = jobs;
        this.policy = policy;
        this.permissions = permissions;

        tool("list_storage_locations", "List active storage locations and their provider types.", CATALOG_VIEW,
                (args, grants) -> connections.catalogLocations().stream().filter(CatalogLocationResponse::active)
                        .map(location -> new Location(location.id(), location.name(), location.providerType(),
                                locationType(location), location.role(), "ACTIVE")).toList());
        tool("get_storage_status", "Show connectivity, available capacity, catalog item count and storage savings. "
                        + "Provide connectionId to probe only that location.", STORAGE_VIEW,
                (args, grants) -> storageStatus(args.uuid("connectionId")), uuid("connectionId", false));
        tool("search_catalog_files", "Search catalog entries by location, literal path prefix and project temperature policy "
                        + "(HOT, WARM, COLD based on last modification). Page numbers start at zero.", CATALOG_VIEW,
                (args, grants) -> search(args), uuid("connectionId", false), choices("storageState", "HOT", "WARM", "COLD"),
                text("pathPrefix", false, 2048), PAGE, LIMIT);
        tool("get_file_details", "Read file size, SHA-256, storage locations, archive state and tags.", CATALOG_VIEW,
                (args, grants) -> details(args.uuid("fileId")), FILE);
        tool("get_file_lineage", "Read historical movements and checksum verification results for a relative path.", CATALOG_VIEW,
                (args, grants) -> {
                    UUID connection = args.uuid("connectionId");
                    connections.get(connection);
                    return lineage.forFile(connection, args.text("path"), args.integer("page", 0), args.integer("limit", 20));
                }, CONNECTION, text("path", true, 2048), PAGE, LIMIT);
        tool("queue_archive", "Queue files or folders for a verified S3 archive. Supply paths, fileIds, or both. "
                        + "COPY keeps the originals. PURGE replaces them with links and requires TRANSFER_PURGE.", TRANSFER_RUN,
                (args, grants) -> queue(TransferJob.Operation.ARCHIVE, args, grants), SOURCE, TARGET, PATHS, FILES,
                choices("mode", "COPY", "PURGE"), text("storageClass", false, 64));
        tool("queue_recall", "Queue a verified recall from S3 to mounted storage. Supply object paths, fileIds, "
                        + "or mounted targetFolderPaths ending in /. S3 copies are retained.", TRANSFER_RUN,
                (args, grants) -> queue(TransferJob.Operation.RECALL, args, grants), SOURCE, TARGET, PATHS, FILES,
                array(text("targetFolderPaths", false, 2048)));
        tool("sync_recall_file", "Recall one file within 1 to 60 seconds. A cold object may return an estimated ready time.",
                TRANSFER_RUN, (args, grants) -> transfers.recallSync(args.uuid("fileId"), args.integer("timeoutSeconds", 60)),
                FILE, integer("timeoutSeconds", 1, 60, 60));
        tool("get_job_status", "Read status, progress, bytes and a page of error logs for one of your jobs.",
                ACTIVITY_VIEW, (args, grants) -> jobStatus(args), JOB, PAGE, LIMIT);
        tool("control_job", "Pause, resume, cancel or retry one of your jobs. A retry creates a new job ID.",
                ACTIVITY_CONTROL, (args, grants) -> control(args, grants), JOB,
                choices("action", "PAUSE", "RESUME", "CANCEL", "RETRY").requiredField());
        tool("trigger_catalog_scan", "Queue discovery of physical entries at a storage location.",
                CATALOG_OPERATE, (args, grants) -> queued(catalog.submit(args.uuid("connectionId"))), CONNECTION);
        tool("get_system_health", "Read the overall Koonj server health status.", OBSERVABILITY_VIEW,
                (args, grants) -> Map.of("status", health.health().getStatus().getCode()));
    }

    private void tool(String name, String description, Permission permission, Handler handler, McpArguments.Field... fields) {
        tools.put(name, new Tool(name, description, permission, List.of(fields), handler));
    }

    List<Definition> definitions(Authentication auth) {
        var grants = permissions.effective(auth);
        return tools.values().stream().filter(tool -> grants.contains(tool.permission())).map(Tool::definition).toList();
    }

    public List<Availability> availability(Set<Permission> grants) {
        return tools.values().stream().map(tool -> new Availability(tool.name(), tool.description(), tool.permission(),
                grants.contains(tool.permission()))).toList();
    }

    Object call(String name, JsonNode arguments, Authentication auth) {
        var tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool.");
        var grants = permissions.effective(auth);
        require(grants, tool.permission());
        return tool.handler().call(new McpArguments(arguments, tool.fields()), grants);
    }

    private static void require(Set<Permission> grants, Permission permission) {
        if (!grants.contains(permission)) throw new PermissionDenied(permission);
    }

    private record Location(UUID id, String name, ProviderType provider, String type, StorageRole role, String state) {}

    private static String locationType(CatalogLocationResponse location) {
        if (location.bucket() != null) return "S3";
        return location.providerType() == ProviderType.SMB ? "SMB" : "LOCAL";
    }

    private Object storageStatus(UUID location) {
        var usage = connections.storageUsage();
        // A connection selector limits remote probes. The aggregate remains project-wide.
        var health = location == null
                ? connections.statusLocations().stream().filter(StorageLocationStatus::active)
                    .map(item -> connections.connectivity(item.id())).toList()
                : List.of(connections.connectivity(location));
        return Map.of("catalogedItems", connections.catalogedItems(), "locations", health,
                "footprints", usage.footprints(), "savings", usage.reclaimed(),
                "reclaimedBytes", usage.reclaimed().stream().mapToLong(StorageUsage.Reclaimed::sizeBytes).sum());
    }

    private record CatalogItem(UUID id, UUID connectionId, String path, EntryType type, long bytes,
                               StorageState archiveState, Temperature temperature) {}

    private record CatalogPage(List<CatalogItem> items, int page, long total, boolean hasMore) {}

    private CatalogPage search(McpArguments args) {
        UUID location = args.uuid("connectionId");
        if (location != null) connections.get(location);
        var thresholds = policy.current();
        Instant now = Instant.now();
        Instant hot = now.minus(thresholds.hotDays(), ChronoUnit.DAYS);
        Instant cold = now.minus(thresholds.coldDays(), ChronoUnit.DAYS);
        String state = args.text("storageState");
        Instant after = "HOT".equals(state) ? hot : "WARM".equals(state) ? cold : null;
        Instant through = "WARM".equals(state) ? hot : "COLD".equals(state) ? cold : null;
        var page = catalog.list(location, null, args.text("pathPrefix"), after, through,
                PageRequest.of(args.integer("page", 0), args.integer("limit", 20), Sort.by("relativePath", "id")));
        return new CatalogPage(page.content().stream().map(file -> new CatalogItem(file.id(), file.connectionId(),
                shorten(file.relativePath(), 512), file.entryType(), file.sizeBytes(), file.storageState(),
                temperature(file.lastModifiedAt(), hot, cold))).toList(), page.page(), page.totalElements(),
                page.page() + 1 < page.totalPages());
    }

    private static Temperature temperature(Instant modified, Instant hot, Instant cold) {
        if (modified == null) return null;
        if (modified.isAfter(hot)) return Temperature.HOT;
        return modified.isAfter(cold) ? Temperature.WARM : Temperature.COLD;
    }

    private Object details(UUID id) {
        var file = catalog.get(id);
        var source = connections.get(file.connectionId());
        var detail = new LinkedHashMap<String, Object>();
        detail.put("file", file);
        detail.put("location", source.getBasePathOrBucket());
        if (file.targetConnectionId() != null) {
            detail.put("archiveLocation", connections.get(file.targetConnectionId()).getBasePathOrBucket());
        }
        detail.put("tags", metadata.fileTags(file.connectionId(), file.relativePath()));
        return detail;
    }

    private Object queue(TransferJob.Operation operation, McpArguments args, Set<Permission> grants) {
        String mode = args.text("mode");
        ArchiveMode archiveMode = mode == null ? ArchiveMode.COPY : ArchiveMode.valueOf(mode);
        if (archiveMode == ArchiveMode.PURGE) require(grants, TRANSFER_PURGE);
        var request = new TransferRequest(args.uuid("sourceConnectionId"), args.uuid("targetConnectionId"),
                args.strings("paths"), args.strings("fileIds").stream().map(UUID::fromString).toList(),
                args.text("storageClass"), archiveMode, false, args.strings("targetFolderPaths"));
        return queued(transfers.submit(operation, request));
    }

    private record JobStatus(UUID jobId, String status, TransferJob.Operation operation, int totalFiles,
                             int processedFiles, int failedFiles, long totalBytes, long transferredBytes,
                             String details, PageResponse<JobEventResponse> errors) {}

    private Object jobStatus(McpArguments args) {
        UUID id = args.uuid("jobId");
        var job = jobs.get(id);
        var events = jobs.events(id, true, PageRequest.of(args.integer("page", 0), args.integer("limit", 20)));
        var errors = new PageResponse<>(events.content().stream().map(event -> new JobEventResponse(event.id(),
                event.recordedAt(), event.level(), shorten(event.path(), 512), shorten(event.message(), 1024))).toList(),
                events.page(), events.size(), events.totalElements(), events.totalPages());
        return new JobStatus(id, status(job.status()), job.operation(), job.totalFiles(), job.processedFiles(),
                job.failedFiles(), job.totalBytes(), job.transferredBytes(), shorten(job.details(), 1024), errors);
    }

    private Object control(McpArguments args, Set<Permission> grants) {
        UUID id = args.uuid("jobId");
        var action = switch (args.text("action")) {
            case "CANCEL" -> JobService.Action.STOP;
            default -> JobService.Action.valueOf(args.text("action"));
        };
        if (action.startsWork()) {
            var original = jobs.get(id);
            if (original.operation() == TransferJob.Operation.SCAN) {
                if (java.util.Collections.disjoint(grants, EnumSet.of(CATALOG_OPERATE, STORAGE_MANAGE))) {
                    throw new PermissionDenied(CATALOG_OPERATE);
                }
            } else {
                require(grants, TRANSFER_RUN);
                if (original.operation() == TransferJob.Operation.ARCHIVE && original.archiveMode() == ArchiveMode.PURGE) {
                    require(grants, TRANSFER_PURGE);
                }
            }
        }
        var job = jobs.control(id, action, grants.contains(TRANSFER_PURGE));
        if (action.startsWork()) {
            if (job.getOperation() == TransferJob.Operation.SCAN) catalog.execute(job);
            else transfers.execute(job);
        }
        return queued(JobResponse.from(job));
    }

    private static Map<String, Object> queued(JobResponse response) {
        return Map.of("jobId", response.id(), "status", status(response.status()));
    }

    private static String status(TransferJob.Status status) {
        return status == TransferJob.Status.PENDING ? "QUEUED" : status.name();
    }

    private static String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end) + "…";
    }
}
