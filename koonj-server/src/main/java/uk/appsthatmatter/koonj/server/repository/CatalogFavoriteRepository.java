package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;
import uk.appsthatmatter.koonj.server.entity.CatalogFavorite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogFavoriteRepository extends JpaRepository<CatalogFavorite, UUID> {
    @EntityGraph(attributePaths = "connection")
    List<CatalogFavorite> findByAccountSubjectOrderByCreatedAtDescIdDesc(String accountSubject);

    Optional<CatalogFavorite> findByAccountSubjectAndConnectionIdAndScopeAndPath(
            String accountSubject, UUID connectionId, Scope scope, String path);

    void deleteByConnectionId(UUID connectionId);
}
