package uk.appsthatmatter.koonj.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.connector.LocalStorageConnector;
import uk.appsthatmatter.koonj.server.connector.StorageConnectorFactory;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.entity.TransferReservation;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.repository.TransferReservationRepository;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
final class TransferReservations {
    private static final PageRequest ALIAS_PAGE = PageRequest.of(0, 1000);
    private final StorageConnectionRepository connections;
    private final StorageConnectorFactory connectors;
    private final FileMetadataRepository files;
    private final TransferReservationRepository reservations;
    private final TransferTransactionLocks locks;
    private final boolean caseInsensitiveMounts;
    private final Map<String, String> aliases;
    private final String mountedDomain;
    private final Map<String, String> mountPools;

    TransferReservations(StorageConnectionRepository connections, StorageConnectorFactory connectors,
                         FileMetadataRepository files, TransferReservationRepository reservations,
                         TransferTransactionLocks locks,
                         @Value("${koonj.transfer.case-insensitive-mounts:true}") boolean caseInsensitiveMounts,
                         @Value("${koonj.transfer.storage-aliases:}") String aliases,
                         @Value("${koonj.transfer.mounted-domain:default}") String mountedDomain,
                         @Value("${koonj.transfer.rabbit.mount-pools:}") String mountPools) {
        this.connections = connections;
        this.connectors = connectors;
        this.files = files;
        this.reservations = reservations;
        this.locks = locks;
        this.caseInsensitiveMounts = caseInsensitiveMounts;
        this.mountedDomain = mountedDomain;
        var bindings = new HashMap<String, String>();
        for (String binding : aliases.split(";")) {
            if (binding.isBlank()) continue;
            String[] pair = binding.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException("Storage aliases must use alias=canonical pairs separated by semicolons");
            }
            bindings.put(pair[0].strip(), pair[1].strip());
        }
        this.aliases = Map.copyOf(bindings);
        var pools = new HashMap<String, String>();
        for (String binding : mountPools.split(";")) {
            if (binding.isBlank()) continue;
            String[] pair = binding.split("=", 2);
            if (pair.length != 2 || !pair[0].strip().startsWith("/") || !pair[1].strip().matches("[a-z0-9-]{1,40}")) {
                throw new IllegalArgumentException("Mount pools must use /path=pool pairs separated by semicolons");
            }
            pools.put(pair[0].strip().replaceFirst("/+$", ""), pair[1].strip());
        }
        this.mountPools = Map.copyOf(pools);
    }

    void acquire(TransferJob job) {
        if (job.getOperation() != TransferJob.Operation.SCAN && job.getPlan() == null) return;
        var storage = new HashMap<UUID, StorageConnection>();
        var ids = new TreeSet<UUID>();
        ids.add(job.getSourceConnectionId());
        if (job.getTargetConnectionId() != null) ids.add(job.getTargetConnectionId());
        for (UUID id : ids) {
            StorageConnection connection = connections.findByIdForUpdate(id, job.getProjectId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Storage connection was not found"));
            if (!connection.isActive()) throw new IllegalArgumentException("Storage connection is inactive");
            storage.put(id, connection);
        }
        var source = storage.get(job.getSourceConnectionId());
        var from = location(source);
        var scopes = new LinkedHashSet<Scope>();
        if (job.getOperation() == TransferJob.Operation.SCAN) {
            job.setDispatchPool(from.mounted() ? pool(from.root()) : "default");
            scopes.add(from.scope("", true));
        } else {
            var target = storage.get(job.getTargetConnectionId());
            var to = location(target);
            job.setDispatchPool(pool(from.mounted() ? from.root() : to.root()));
            addTransferScopes(job, source, target, from, to, scopes);
        }
        var namespaces = scopes.stream().map(Scope::namespace).distinct().toList();
        locks.acquire(namespaces);
        boolean scan = job.getOperation() == TransferJob.Operation.SCAN;
        var existing = index(reservations.findByNamespaceIn(namespaces).stream()
                .filter(row -> !scan || !row.isScan()).toList());
        for (Scope scope : scopes) {
            var paths = existing.get(scope.namespace());
            if (paths != null && paths.overlaps(scope)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A transfer or catalog scan has reserved an overlapping storage path");
            }
        }
        // Remove exact aliases already covered by a selected subtree.
        var requested = new HashMap<String, Paths>();
        for (Scope scope : scopes) if (scope.subtree()) requested.computeIfAbsent(scope.namespace(), ignored -> new Paths()).add(scope);
        reservations.saveAll(scopes.stream().filter(scope -> scope.subtree() || !requested.containsKey(scope.namespace())
                        || !requested.get(scope.namespace()).covers(scope.path()))
                .map(scope -> new TransferReservation(job.getId(), scope.namespace(), scope.path(), scope.subtree(), scan)).toList());
    }

    private void addTransferScopes(TransferJob job, StorageConnection source, StorageConnection target,
                                   Location from, Location to, Set<Scope> scopes) {
        var plan = job.getPlan();
        for (String path : plan.sourcePaths()) {
            boolean subtree = !plan.exactPaths().contains(path);
            scopes.add(from.scope(path, subtree));
            if (subtree || !plan.restorePaths().containsKey(path)) scopes.add(to.scope(path, subtree));
        }
        plan.restorePaths().values().forEach(path -> scopes.add(to.scope(path, false)));
        for (String folder : plan.targetFolders()) {
            scopes.add(to.scope(folder, true));
            scopes.add(from.scope(folder, true));
            String after = "";
            while (true) {
                var page = files.recallLinks(target.getId(), JobService.prefixPattern(folder), after, true, ALIAS_PAGE);
                for (var link : page) {
                    if (!link.getRelativePath().startsWith(folder)) continue;
                    String key;
                    try {
                        key = FileTransferService.recallKey(source, link.getId(), link.getTargetId(),
                                link.getSymlinkTarget(), link.getS3Key());
                    } catch (IllegalArgumentException invalidReference) {
                        continue;
                    }
                    if (key != null) scopes.add(from.scope(key, false));
                }
                if (page.size() < ALIAS_PAGE.getPageSize()) break;
                after = page.getLast().getRelativePath();
            }
        }
    }

    void release(UUID jobId) { reservations.release(jobId); }

    void verifyScan(TransferJob job, StorageConnection source) {
        Scope scope = location(source).scope("", true);
        if (reservations.findByJobId(job.getId()).stream().noneMatch(row -> row.isSubtree()
                && scope.namespace().equals(row.getNamespace()) && scope.path().equals(row.getPath()))) {
            throw new IllegalStateException("Scan root changed after reservation. Submit a new scan.");
        }
    }

    Footprint footprint(TransferJob job, StorageConnection source, StorageConnection target) {
        var byNamespace = index(reservations.findByJobId(job.getId()));
        var from = location(source);
        var to = location(target);
        return (key, path) -> covers(byNamespace, from.scope(key, false)) && covers(byNamespace, to.scope(path, false));
    }

    private static boolean covers(Map<String, Paths> namespaces, Scope scope) {
        var paths = namespaces.get(scope.namespace());
        return paths != null && paths.covers(scope.path());
    }

    private static Map<String, Paths> index(List<TransferReservation> rows) {
        var result = new HashMap<String, Paths>();
        for (var row : rows) {
            result.computeIfAbsent(row.getNamespace(), ignored -> new Paths())
                    .add(new Scope(row.getNamespace(), row.getPath(), row.isSubtree()));
        }
        return result;
    }

    private Location location(StorageConnection connection) {
        var connector = connectors.resolve(connection.getProviderType());
        if (connector.objectStorage()) {
            String endpoint = endpoint(connection.getEndpointUrl(), connection.getRegion());
            String namespace = "s3:" + endpoint + "/" + connection.getBasePathOrBucket();
            return new Location(digest(aliases.getOrDefault(namespace, namespace)), "", false, false);
        }
        try {
            String path = ((LocalStorageConnector) connector).storageRoot(connection).toString().replace('\\', '/');
            String identity = "mount:" + path;
            String prefix = aliases.keySet().stream().filter(key -> key.startsWith("mount:")
                            && (identity.equals(key) || identity.startsWith(key + "/")))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            if (prefix != null) {
                String canonical = aliases.get(prefix);
                if (!canonical.startsWith("mount:")) throw new IllegalArgumentException("Mount aliases must map to mounts");
                path = canonical.substring("mount:".length()) + identity.substring(prefix.length());
            }
            return new Location(digest("mount:" + mountedDomain), path, caseInsensitiveMounts, true);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not resolve the storage root", failure);
        }
    }

    private String pool(String root) {
        return mountPools.entrySet().stream()
                .filter(entry -> root.equals(entry.getKey()) || root.startsWith(entry.getKey() + "/"))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue).orElse("default");
    }

    private static String endpoint(String configured, String region) {
        if (configured == null || configured.isBlank()) {
            if (region != null && region.startsWith("cn-")) return "aws-cn";
            if (region != null && region.startsWith("us-gov-")) return "aws-us-gov";
            return "aws";
        }
        URI uri = URI.create(configured).normalize();
        String host = Objects.requireNonNull(uri.getHost(), "S3 endpoint must include a host").toLowerCase(Locale.ROOT);
        if (host.equals("s3.amazonaws.com") || ((host.startsWith("s3.") || host.startsWith("s3-") || host.startsWith("s3-fips."))
                && (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn")))) {
            if (host.endsWith(".cn")) return "aws-cn";
            return host.contains("us-gov-") ? "aws-us-gov" : "aws";
        }
        int port = uri.getPort();
        if (port == -1) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return host + ":" + port + uri.getPath().replaceFirst("/+$", "");
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @FunctionalInterface
    interface Footprint { boolean covers(String source, String target); }

    record Scope(String namespace, String path, boolean subtree) {}

    private record Location(String namespace, String root, boolean caseInsensitive, boolean mounted) {
        Scope scope(String path, boolean subtree) {
            String full = (root + "/" + path).replaceAll("^/+|/+$", "");
            if (caseInsensitive) full = java.text.Normalizer.normalize(full, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            return new Scope(namespace, full, subtree);
        }
    }

    static final class Paths {
        private final NavigableSet<String> paths = new TreeSet<>();
        private final Set<String> subtrees = new HashSet<>();

        void add(Scope scope) {
            paths.add(scope.path());
            if (scope.subtree()) subtrees.add(scope.path());
        }

        boolean covers(String path) {
            if (paths.contains(path) || subtrees.contains("")) return true;
            for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
                if (subtrees.contains(path.substring(0, slash))) return true;
            }
            return false;
        }

        boolean overlaps(Scope scope) {
            if (covers(scope.path())) return true;
            if (!scope.subtree()) return false;
            if (scope.path().isEmpty()) return !paths.isEmpty();
            String prefix = scope.path() + "/";
            String first = paths.ceiling(prefix);
            return first != null && first.startsWith(prefix);
        }
    }
}
