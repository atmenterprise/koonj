package uk.appsthatmatter.koonj.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.connector.StoragePaths;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataResponse;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataValues;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataValues.Property;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Mode;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Target;
import uk.appsthatmatter.koonj.server.entity.CatalogAnnotation;
import uk.appsthatmatter.koonj.server.repository.CatalogAnnotationRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class CatalogMetadataService {
    private static final CatalogMetadataValues EMPTY = new CatalogMetadataValues(List.of(), List.of());

    private final StorageConnectionRepository connections;
    private final CatalogAnnotationRepository annotations;

    public CatalogMetadataService(StorageConnectionRepository connections, CatalogAnnotationRepository annotations) {
        this.connections = connections;
        this.annotations = annotations;
    }

    @Transactional(readOnly = true)
    public List<CatalogMetadataResponse> list(UUID connectionId) {
        if (!connections.existsById(connectionId)) throw missingConnection();
        return annotations.findByConnectionIdOrderByPathAscScopeAsc(connectionId).stream()
                .map(item -> new CatalogMetadataResponse(item.getScope(), item.getPath(), item.getAttributes())).toList();
    }

    @Transactional
    public void save(UUID connectionId, CatalogMetadataWriteRequest request) {
        var connection = connections.findByIdForUpdate(connectionId).orElseThrow(CatalogMetadataService::missingConnection);
        var targets = request.targets().stream().map(CatalogMetadataService::normalize).distinct().toList();
        for (Target target : targets) {
            var existing = annotations.findByConnectionIdAndScopeAndPath(connectionId, target.scope(), target.path());
            var previous = request.mode() == Mode.MERGE ? existing.map(CatalogAnnotation::getAttributes).orElse(EMPTY) : EMPTY;
            var values = merge(previous, request.values());
            if (values.tags().isEmpty() && values.properties().isEmpty()) {
                existing.ifPresent(annotations::delete);
            } else {
                var entry = existing.orElseGet(CatalogAnnotation::new);
                entry.setConnection(connection);
                entry.setScope(target.scope());
                entry.setPath(target.path());
                entry.setAttributes(values);
                annotations.save(entry);
            }
        }
    }

    private static Target normalize(Target target) {
        String path = StoragePaths.selection(target.path());
        if (path.isBlank() || path.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw new IllegalArgumentException("Metadata requires a relative file or folder path of at most 2048 bytes");
        }
        return new Target(target.scope(), path);
    }

    private static CatalogMetadataValues merge(CatalogMetadataValues previous, CatalogMetadataValues incoming) {
        var tags = new LinkedHashMap<String, String>();
        previous.tags().forEach(tag -> tags.put(tag.toLowerCase(Locale.ROOT), tag));
        incoming.tags().forEach(value -> {
            String tag = value.strip();
            tags.put(tag.toLowerCase(Locale.ROOT), tag);
        });
        var properties = new LinkedHashMap<String, Property>();
        previous.properties().forEach(property -> properties.put(property.name().toLowerCase(Locale.ROOT), property));
        incoming.properties().forEach(property -> {
            String name = property.name().strip();
            properties.put(name.toLowerCase(Locale.ROOT), new Property(name, property.value().strip()));
        });
        if (tags.size() > 100 || properties.size() > 100) throw new IllegalArgumentException("An item can have at most 100 tags and 100 properties");
        return new CatalogMetadataValues(List.copyOf(tags.values()), List.copyOf(properties.values()));
    }

    private static ResponseStatusException missingConnection() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Storage connection was not found");
    }
}
