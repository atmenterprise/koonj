package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteRequest;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteResponse;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;
import uk.appsthatmatter.koonj.server.service.CatalogFavoriteService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog/favorites")
@PreAuthorize(KoonjRoles.READ)
public class CatalogFavoriteController {
    private final CatalogFavoriteService favorites;

    public CatalogFavoriteController(CatalogFavoriteService favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    public List<CatalogFavoriteResponse> list(Authentication authentication) {
        return favorites.list(account(authentication));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(Authentication authentication, @Valid @RequestBody CatalogFavoriteRequest request) {
        favorites.setFavorite(account(authentication), request);
    }

    private static String account(Authentication authentication) {
        return (authentication instanceof JwtAuthenticationToken ? "oidc:" : "basic:") + authentication.getName();
    }
}
