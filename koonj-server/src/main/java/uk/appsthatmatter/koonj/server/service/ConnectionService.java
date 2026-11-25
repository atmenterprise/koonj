package uk.appsthatmatter.koonj.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.*;
import uk.appsthatmatter.koonj.server.storageauth.StorageAuthentication;
import uk.appsthatmatter.koonj.server.storageauth.StorageCredentialEncryption;
import uk.appsthatmatter.koonj.server.storageauth.CredentialTarget;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConnectionService {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionService.class);
    private final StorageConnectionRepository connections;
    private final FileMetadataRepository files;
    private final TransferJobRepository jobs;
    private final StorageConnectorFactory connectors;
    private final CatalogAnnotationRepository annotations;
    private final CatalogFavoriteRepository favorites;
    private final StorageAuthentication authentication;
    private final StorageCredentialEncryption encryption;

    public ConnectionService(StorageConnectionRepository connections, FileMetadataRepository files,
                             TransferJobRepository jobs, StorageConnectorFactory connectors,
                             CatalogAnnotationRepository annotations, CatalogFavoriteRepository favorites,
                             StorageAuthentication authentication, StorageCredentialEncryption encryption) {
        this.connections = connections;
        this.files = files;
        this.jobs = jobs;
        this.connectors = connectors;
        this.annotations = annotations;
        this.favorites = favorites;
        this.authentication = authentication;
        this.encryption = encryption;
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
        applySettings(connection, request);
        if (connection.getAuthMode() == StorageAuthMode.ENCRYPTED_CREDENTIALS) {
            requireSubmittedCredentials(request.credentials());
            // Bind the credential to its final ID before Hibernate captures the insert state.
            connection.setId(UUID.randomUUID());
            replaceCredential(connection, request.credentials());
        }
        return ConnectionResponse.from(connections.saveAndFlush(connection));
    }

    @Transactional
    public ConnectionResponse update(UUID id, ConnectionRequest request) {
        StorageConnection existing = storageForUpdate(id);
        requireIdle(id);
        boolean migrating = existing.getAuthMode() == StorageAuthMode.MIGRATION_REQUIRED;
        boolean reclassifyingS3 = migrating && existing.getProviderType() == ProviderType.AWS_S3
                && existing.getEndpointUrl() != null && request.providerType() == ProviderType.CUSTOM_S3;
        if (referenced(id) && ((!reclassifyingS3 && existing.getProviderType() != request.providerType())
                || !existing.getBasePathOrBucket().equals(request.basePathOrBucket())
                || !Objects.equals(existing.getEndpointUrl(), optional(request.endpointUrl()))
                || !Objects.equals(existing.getRegion(), optional(request.region()))
                || !Objects.equals(existing.getObjectPrefix(), prefix(request.objectPrefix())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Register a new connection to change the storage location of cataloged files");
        }
        CredentialTarget previousTarget = CredentialTarget.from(existing);
        applySettings(existing, request);
        if (existing.getAuthMode() == StorageAuthMode.ENCRYPTED_CREDENTIALS) {
            if (request.credentials() != null) {
                checkProbe(existing, request.credentials());
                replaceCredential(existing, request.credentials());
            } else if (existing.getCredentialEnvelope() == null || !previousTarget.equals(CredentialTarget.from(existing))) {
                throw new IllegalArgumentException("Enter new credentials for this storage target");
            }
        } else {
            existing.setCredentialEnvelope(null);
            if (migrating) checkProbe(existing, null);
        }
        return ConnectionResponse.from(connections.saveAndFlush(existing));
    }

    @Transactional
    public ConnectionResponse rotateCredential(UUID id, StorageCredentialInput input) {
        StorageConnection existing = storageForUpdate(id);
        if (existing.getAuthMode() != StorageAuthMode.ENCRYPTED_CREDENTIALS) {
            throw new IllegalArgumentException("Select encrypted credentials before rotating this connection");
        }
        requireEncryption();
        checkProbe(existing, input);
        replaceCredential(existing, input);
        return ConnectionResponse.from(connections.saveAndFlush(existing));
    }

    private void checkProbe(StorageConnection connection, StorageCredentialInput input) {
        ConnectionHealth verification = probe(connection, input);
        if (!verification.healthy()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, verification.message());
        }
    }

    private void replaceCredential(StorageConnection connection, StorageCredentialInput input) {
        connection.setCredentialEnvelope(encryption.encrypt(CredentialTarget.from(connection), input));
        LOG.info("Encrypted storage credential prepared project={} connection={}", connection.getProjectId(), connection.getId());
    }

    private void requireEncryption() {
        if (!encryption.enabled()) throw new IllegalArgumentException("Configure a storage credential wrapping key for this deployment");
    }

    private static void requireSubmittedCredentials(StorageCredentialInput input) {
        if (input == null) throw new IllegalArgumentException("Enter the storage access key ID and secret access key");
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
            LOG.error("Connectivity check failed for storage connection {} ({})", id, failure.getClass().getName());
            return new ConnectionHealth(id, false, StorageErrors.describe(failure));
        }
        StorageCapacity capacity;
        try {
            capacity = connector.capacity(connection).orElse(null);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Capacity check failed for storage connection {} ({})", id, failure.getClass().getName());
            capacity = null;
        }
        return new ConnectionHealth(id, true, "Storage is reachable and can be listed", capacity);
    }

    @Transactional(readOnly = true)
    public StorageUsage storageUsage() {
        return new StorageUsage(files.storageFootprints(ProjectScope.id()), files.reclaimedStorage(ProjectScope.id()));
    }

    public long catalogedItems() {
        return files.countByConnectionProjectIdAndStorageStateNot(ProjectScope.id(), StorageState.PURGED_LOCAL);
    }

    public boolean allowsAwsDefaultChain() {
        return authentication.allowsDefaultChain();
    }

    public boolean encryptedCredentialsAvailable() {
        return encryption.enabled();
    }

    public String awsExternalId(String roleArn) {
        return authentication.externalId(ProjectScope.id(), roleArn);
    }

    public ConnectionHealth testConnection(UUID id) {
        return probe(get(id), null);
    }

    public ConnectionHealth testDraft(ConnectionRequest request) {
        var connection = new StorageConnection();
        connection.setProjectId(ProjectScope.id());
        applySettings(connection, request);
        if (connection.getAuthMode() == StorageAuthMode.ENCRYPTED_CREDENTIALS) {
            requireSubmittedCredentials(request.credentials());
        }
        return probe(connection, request.credentials());
    }

    private ConnectionHealth probe(StorageConnection connection, StorageCredentialInput input) {
        try {
            var connector = connectors.resolve(connection.getProviderType());
            if (input == null) connector.testConnection(connection);
            else connector.testConnection(connection, AwsBasicCredentials.create(input.accessKeyId(), input.secretAccessKey()));
            return new ConnectionHealth(connection.getId(), true, "Storage access verified");
        } catch (IOException | RuntimeException failure) {
            LOG.error("Storage access test failed for provider {} and connection {} ({})",
                    connection.getProviderType(), connection.getId(), failure.getClass().getName());
            return new ConnectionHealth(connection.getId(), false, StorageErrors.describe(failure));
        }
    }

    private void applySettings(StorageConnection connection, ConnectionRequest request) {
        ProviderType provider = request.providerType();
        boolean objectStorage = connectors.resolve(provider).objectStorage();
        authentication.configure(connection, request, objectStorage);
        if (connection.getAuthMode() == StorageAuthMode.ENCRYPTED_CREDENTIALS) requireEncryption();
        if (!objectStorage) {
            if (request.objectPrefix() != null || request.region() != null) {
                throw new IllegalArgumentException("Mounted storage accepts only filesystem settings");
            }
            if (provider == ProviderType.SMB) SmbStorageConnector.validateShare(request.basePathOrBucket());
            else if (!Path.of(request.basePathOrBucket()).isAbsolute()) {
                throw new IllegalArgumentException("Mounted storage requires an absolute root path");
            }
        }
        connection.setName(request.name());
        connection.setProviderType(provider);
        connection.setRole(request.role());
        connection.setBasePathOrBucket(request.basePathOrBucket());
        connection.setEndpointUrl(request.endpointUrl());
        connection.setRegion(optional(request.region()));
        connection.setObjectPrefix(prefix(request.objectPrefix()));
        if (request.pathStyleAccessEnabled() != null) connection.setPathStyleAccessEnabled(request.pathStyleAccessEnabled());
        if (request.active() != null) connection.setActive(request.active());
    }

    private static String prefix(String value) {
        if (value == null) return null;
        String prefix = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (prefix.isBlank()) throw new IllegalArgumentException("Enter a relative object prefix");
        StoragePaths.requireSafe(prefix);
        return prefix;
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value; }
}
