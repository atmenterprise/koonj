package uk.appsthatmatter.koonj.server.service;

import uk.appsthatmatter.koonj.server.security.ProjectScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.connector.StoragePaths;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteRequest;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteResponse;
import uk.appsthatmatter.koonj.server.entity.CatalogFavorite;
import uk.appsthatmatter.koonj.server.repository.CatalogFavoriteRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class CatalogFavoriteService {
    private final CatalogFavoriteRepository favorites;
    private final StorageConnectionRepository connections;

    public CatalogFavoriteService(CatalogFavoriteRepository favorites, StorageConnectionRepository connections) {
        this.favorites = favorites;
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public List<CatalogFavoriteResponse> list(String subject) {
        return favorites.findByAccountSubjectAndConnectionProjectIdOrderByCreatedAtDescIdDesc(subject, ProjectScope.id()).stream()
                .map(CatalogFavoriteResponse::from).toList();
    }

    @Transactional
    public void setFavorite(String subject, CatalogFavoriteRequest request) {
        String path = StoragePaths.selection(request.path());
        if (path.isBlank() || path.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw new IllegalArgumentException("A favorite requires a relative file or folder path of at most 2048 bytes");
        }
        var connection = connections.findByIdForUpdate(request.connectionId(), ProjectScope.id()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Storage connection was not found"));
        var existing = favorites.findByAccountSubjectAndConnectionIdAndScopeAndPath(
                subject, request.connectionId(), request.scope(), path);
        if (!request.favorite()) {
            existing.ifPresent(favorites::delete);
        } else if (existing.isEmpty()) {
            var favorite = new CatalogFavorite();
            favorite.setAccountSubject(subject);
            favorite.setConnection(connection);
            favorite.setPath(path);
            favorite.setScope(request.scope());
            favorite.setCreatedAt(Instant.now());
            favorites.save(favorite);
        }
    }
}
