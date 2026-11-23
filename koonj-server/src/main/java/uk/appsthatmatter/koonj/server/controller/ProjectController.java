package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.security.PermissionRules;
import uk.appsthatmatter.koonj.server.service.ProjectService;
import uk.appsthatmatter.koonj.server.service.ProjectService.AccountView;
import uk.appsthatmatter.koonj.server.service.ProjectService.ProjectView;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;

    public ProjectController(ProjectService projects) { this.projects = projects; }
    public record ProjectRequest(@NotBlank @Size(max = 100) String name) {}
    public record MembershipRequest(@NotBlank @Size(max = 255) String subject) {}

    @PostMapping("/session")
    @PreAuthorize("isAuthenticated()")
    public List<ProjectView> session(Authentication auth) { return projects.onboard(auth); }

    @GetMapping
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    public List<ProjectView> list() { return projects.all(); }

    @PostMapping
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(Authentication auth, @Valid @RequestBody ProjectRequest request) {
        return projects.create(auth, request.name());
    }

    @GetMapping("/users")
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    public List<AccountView> users() { return projects.accounts(); }

    @GetMapping("/{id}/members")
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    public List<AccountView> members(@PathVariable UUID id) { return projects.members(id); }

    @PutMapping("/{id}/members")
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable UUID id, @Valid @RequestBody MembershipRequest request) {
        projects.assign(id, request.subject());
    }

    @DeleteMapping("/{id}/members")
    @PreAuthorize(PermissionRules.PROJECTS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, @RequestParam String subject) { projects.remove(id, subject); }
}
