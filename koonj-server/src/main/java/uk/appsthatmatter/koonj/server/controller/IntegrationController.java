package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.entity.IntegrationEndpoint;
import uk.appsthatmatter.koonj.server.repository.IntegrationEndpointRepository;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;

import uk.appsthatmatter.koonj.server.security.ProjectScope;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/integrations")
@PreAuthorize(KoonjRoles.ADMIN_ONLY)
public class IntegrationController {
    private final IntegrationEndpointRepository endpoints;

    public IntegrationController(IntegrationEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    public record Settings(@NotNull IntegrationEndpoint.Kind kind, @NotBlank @Size(max = 120) String name,
                           @NotBlank @Size(max = 2048) String url, boolean enabled) {}
    public record Entry(UUID id, IntegrationEndpoint.Kind kind, String name, String url, boolean enabled) {
        static Entry from(IntegrationEndpoint endpoint) {
            return new Entry(endpoint.getId(), endpoint.getKind(), endpoint.getName(), endpoint.getUrl(), endpoint.isEnabled());
        }
    }

    @GetMapping
    public List<Entry> list(@RequestParam IntegrationEndpoint.Kind kind) {
        return endpoints.findByProjectIdAndKindOrderByNameAscIdAsc(ProjectScope.id(), kind).stream().map(Entry::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@Valid @RequestBody Settings settings) {
        var endpoint = new IntegrationEndpoint();
        endpoint.setProjectId(ProjectScope.id());
        return save(endpoint, settings);
    }

    @PutMapping("/{id}")
    @Transactional
    public Entry update(@PathVariable UUID id, @Valid @RequestBody Settings settings) {
        var endpoint = find(id);
        if (endpoint.getKind() != settings.kind()) throw new IllegalArgumentException("Integration type cannot change");
        return save(endpoint, settings);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        endpoints.delete(find(id));
    }

    private IntegrationEndpoint find(UUID id) {
        return endpoints.findById(id).filter(endpoint -> ProjectScope.includes(endpoint.getProjectId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integration was not found"));
    }

    private Entry save(IntegrationEndpoint endpoint, Settings settings) {
        URI uri = URI.create(settings.url().strip());
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use an HTTP(S) endpoint URL without credentials, query parameters, or a fragment");
        }
        endpoint.setKind(settings.kind());
        endpoint.setName(settings.name().strip());
        endpoint.setUrl(uri.toString());
        endpoint.setEnabled(settings.enabled());
        return Entry.from(endpoints.save(endpoint));
    }
}
