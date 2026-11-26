package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import uk.appsthatmatter.koonj.server.service.ProjectInvitations;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.service.ProjectService;
import uk.appsthatmatter.koonj.server.service.ProjectService.AccountView;
import uk.appsthatmatter.koonj.server.service.ProjectService.ProjectView;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;

    private final ProjectInvitations invitations;
    public record ProjectRequest(@NotBlank @Size(max = 100) String name) {}

    @PostMapping("/session")
    @PreAuthorize("isAuthenticated()")
    public List<ProjectView> session(Authentication auth) { return projects.onboard(auth); }

    @GetMapping
    public List<ProjectView> list(Authentication auth) { return projects.onboard(auth); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(Authentication auth, @Valid @RequestBody ProjectRequest request) {
        return projects.create(auth, request.name());
    }

    @GetMapping("/{id}/members")
    public List<AccountView> members(Authentication auth, @PathVariable UUID id) { return projects.members(auth, id); }

    @DeleteMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable UUID id, @RequestParam String subject) { projects.remove(auth, id, subject); }

    public record ChannelSelection(@jakarta.validation.constraints.NotNull UUID channelId) {}

    @GetMapping("/{id}/invitation-channel")
    public ProjectInvitations.EmailSettings emailSettings(Authentication auth, @PathVariable UUID id) {
        return invitations.emailSettings(auth, id);
    }

    @PutMapping("/{id}/invitation-channel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void selectChannel(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ChannelSelection selection) {
        invitations.selectChannel(auth, id, selection.channelId());
    }

    public record Invite(@NotBlank @Size(max = 320) String email) {}
    public record Accept(@NotBlank @Size(max = 128) String token) {}

    @PostMapping("/{id}/invitations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void invite(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Invite request) {
        invitations.invite(auth, id, request.email());
    }

    @GetMapping("/{id}/invitations")
    public List<ProjectInvitations.Pending> invitations(Authentication auth, @PathVariable UUID id) {
        return invitations.list(auth, id);
    }

    @PostMapping("/{id}/invitations/{inviteId}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(Authentication auth, @PathVariable UUID id, @PathVariable UUID inviteId) {
        invitations.resend(auth, id, inviteId);
    }

    @DeleteMapping("/{id}/invitations/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth, @PathVariable UUID id, @PathVariable UUID inviteId) {
        invitations.revoke(auth, id, inviteId);
    }

    @PostMapping("/invitations/accept")
    public UUID accept(Authentication auth, @Valid @RequestBody Accept request) {
        return invitations.accept(auth, request.token());
    }
}
