package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;
import uk.appsthatmatter.koonj.server.entity.CatalogAnnotation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAnnotationRepository extends JpaRepository<CatalogAnnotation, UUID> {
    List<CatalogAnnotation> findByConnectionIdOrderByPathAscScopeAsc(UUID connectionId);
    Optional<CatalogAnnotation> findByConnectionIdAndScopeAndPath(UUID connectionId, Scope scope, String path);
    void deleteByConnectionId(UUID connectionId);
}
