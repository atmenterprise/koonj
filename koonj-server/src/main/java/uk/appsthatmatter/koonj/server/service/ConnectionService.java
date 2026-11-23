package uk.appsthatmatter.koonj.server.service;

import uk.appsthatmatter.koonj.server.security.ProjectScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConnectionService {
    private final StorageConnectionRepository connections;
    private final FileMetadataRepository files;
    private final TransferJobRepository jobs;
    private final StorageConnectorFactory connectors;
    private final CatalogAnnotationRepository annotations;
    private final CatalogFavoriteRepository favorites;

    public ConnectionService(StorageConnectionRepository connections, FileMetadataRepository files,
                             TransferJobRepository jobs, StorageConnectorFactory connectors,
                             CatalogAnnotationRepository annotations, CatalogFavoriteRepository favorites) {
        this.connections = connections;
        this.files = files;
        this.jobs = jobs;
        this.connectors = connectors;
        this.annotations = annotations;
        this.favorites = favorites;
    }

    public StorageConnection get(UUID id) {
        return connections.findByIdAndProjectId(id, ProjectScope.id()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Storage connection was not found"));
    }

    public StorageConnection active(UUID id) {
        StorageConnection connection = get(id);
        if (!connection.isActive()) throw new IllegalArgumentException("Storage connection is inactive");
        return connection;
    }

    public List<ConnectionResponse> list() {
        return connections.findByProjectIdOrderByNameAsc(ProjectScope.id()).stream().map(ConnectionResponse::from).toList();
    }

    public List<StorageLocationStatus> statusLocations() {
        return connections.findByProjectIdOrderByNameAsc(ProjectScope.id()).stream().map(StorageLocationStatus::from).toList();
    }

    public List<CatalogLocationResponse> catalogLocations() {
        return connections.findByProjectIdOrderByNameAsc(ProjectScope.id()).stream().map(connection -> {
            boolean objectStorage = connectors.resolve(connection.getProviderType()).objectStorage();
            return new CatalogLocationResponse(connection.getId(), connection.getName(), connection.getProviderType(),
                    connection.getRole(), objectStorage ? connection.getBasePathOrBucket() : null, connection.isActive());
        }).toList();
    }

    @Transactional
    public ConnectionResponse create(ConnectionRequest request) {
        var connection = new StorageConnection();
        connection.setProjectId(ProjectScope.id());
        return save(connection, request);
    }

    @Transactional
    public ConnectionResponse update(UUID id, ConnectionRequest request) {
        StorageConnection existing = storageForUpdate(id);
        requireIdle(id);
        if (referenced(id) && (existing.getProviderType() != request.providerType()
                || !existing.getBasePathOrBucket().equals(request.basePathOrBucket())
                || !Objects.equals(existing.getEndpointUrl(), optional(request.endpointUrl()))
                || !Objects.equals(existing.getRegion(), optional(request.region())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Register a new connection to change the storage location of cataloged files");
        }
        return save(existing, request);
    }

    @Transactional
    public void delete(UUID id) {
        StorageConnection connection = storageForUpdate(id);
        requireIdle(id);
        if (referenced(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Connection is referenced by the catalog. Set active to false to retire it.");
        annotations.deleteByConnectionId(id);
        favorites.deleteByConnectionId(id);
        connections.delete(connection);
    }

    private StorageConnection storageForUpdate(UUID id) {
        return connections.findByIdForUpdate(id, ProjectScope.id()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Storage connection was not found"));
    }

    private boolean referenced(UUID id) {
        return files.existsByConnectionIdOrTargetConnectionId(id, id);
    }

    private void requireIdle(UUID id) {
        if (jobs.countActive(id, List.of(TransferJob.Status.PENDING, TransferJob.Status.RUNNING, TransferJob.Status.PAUSING,
                TransferJob.Status.PAUSED, TransferJob.Status.STOPPING)) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection has an active job");
        }
    }

    public ConnectionHealth connectivity(UUID id) {
        StorageConnection connection = get(id);
        if (!connection.isActive()) return new ConnectionHealth(id, false, "Connection is inactive");
        var connector = connectors.resolve(connection.getProviderType());
        try {
            connector.checkConnectivity(connection);
        } catch (IOException | RuntimeException failure) {
            return new ConnectionHealth(id, false, StorageErrors.describe(failure));
        }
        StorageCapacity capacity;
        try {
            capacity = connector.capacity(connection).orElse(null);
        } catch (IOException | RuntimeException failure) {
            capacity = null;
        }
        return new ConnectionHealth(id, true, "Storage is reachable and can be listed", capacity);
    }

    @Transactional(readOnly = true)
    public StorageUsage storageUsage() {
        return new StorageUsage(files.storageFootprints(ProjectScope.id()), files.reclaimedStorage(ProjectScope.id()));
    }

    public ConnectionHealth testConnection(UUID id) {
        return probe(get(id));
    }

    public ConnectionHealth testDraft(ConnectionRequest request) {
        var connection = new StorageConnection();
        connection.setProjectId(ProjectScope.id());
        applySettings(connection, request);
        return probe(connection);
    }

    private ConnectionHealth probe(StorageConnection connection) {
        try {
            connectors.resolve(connection.getProviderType()).testConnection(connection);
            return new ConnectionHealth(connection.getId(), true, "Storage access verified");
        } catch (IOException | RuntimeException failure) {
            return new ConnectionHealth(connection.getId(), false, StorageErrors.describe(failure));
        }
    }

    private ConnectionResponse save(StorageConnection connection, ConnectionRequest request) {
        applySettings(connection, request);
        return ConnectionResponse.from(connections.saveAndFlush(connection));
    }

    private void applySettings(StorageConnection connection, ConnectionRequest request) {
        ProviderType provider = request.providerType();
        String endpoint = optional(request.endpointUrl());
        String access = credential(request.accessKeyOrUsername(), connection.getAccessKeyOrUsername());
        String secret = credential(request.secretKeyOrPassword(), connection.getSecretKeyOrPassword());
        if (connectors.resolve(provider).objectStorage()) {
            if (provider != ProviderType.AWS_S3 && endpoint == null) {
                throw new IllegalArgumentException("Object storage provider requires an endpoint URL");
            }
            if (endpoint != null) validateEndpoint(endpoint);
            if ((access == null) != (secret == null) || (provider != ProviderType.AWS_S3 && access == null)) {
                throw new IllegalArgumentException("Supply both static credential fields");
            }
        } else if (provider == ProviderType.SMB) {
            SmbStorageConnector.validateShare(request.basePathOrBucket());
        } else if (!Path.of(request.basePathOrBucket()).isAbsolute()) {
            throw new IllegalArgumentException("Mounted storage requires an absolute root path");
        }
        connection.setName(request.name());
        connection.setProviderType(provider);
        connection.setRole(request.role());
        connection.setBasePathOrBucket(request.basePathOrBucket());
        connection.setEndpointUrl(endpoint);
        connection.setRegion(optional(request.region()));
        connection.setAccessKeyOrUsername(access);
        connection.setSecretKeyOrPassword(secret);
        if (request.pathStyleAccessEnabled() != null) connection.setPathStyleAccessEnabled(request.pathStyleAccessEnabled());
        if (request.active() != null) connection.setActive(request.active());
    }

    private static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Endpoint must be a valid HTTP(S) URL");
        }
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Endpoint must be an HTTP(S) URL without credentials, query or fragment");
        }
    }

    private static String credential(String value, String existing) {
        return value == null || value.equals("********") ? existing : optional(value);
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value; }
}
