package uk.appsthatmatter.koonj.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.notification.*;
import uk.appsthatmatter.koonj.server.entity.ProjectMembership;
import uk.appsthatmatter.koonj.server.repository.ProjectMembershipRepository;
import uk.appsthatmatter.koonj.server.repository.ProjectRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ProjectInvitations {
    private final ProjectService projects;
    private final ProjectMembershipRepository memberships;
    private final ProjectInvitationRepository invitations;
    private final ProjectRepository projectRows;

    private final NotificationChannelRepository channels;

    public record Channel(UUID id, String name) {}
    public record EmailSettings(UUID selected, List<Channel> channels) {}

    public EmailSettings emailSettings(Authentication auth, UUID id) {
        var project = projects.admin(auth, id).getProject();
        var choices = channels.findByTypeAndEnabledTrueOrderByNameAscIdAsc(NotificationChannel.Type.EMAIL)
                .stream().map(channel -> new Channel(channel.getId(), channel.getName())).toList();
        return new EmailSettings(project.getInvitationChannelId(), choices);
    }

    public void selectChannel(Authentication auth, UUID id, UUID channelId) {
        var project = projects.admin(auth, id).getProject();
        project.setInvitationChannelId(requireChannel(channelId));
    }

    private UUID requireChannel(UUID id) {
        if (id == null || channels.findByIdAndTypeAndEnabledTrue(id, NotificationChannel.Type.EMAIL).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select an enabled email channel for invitations");
        return id;
    }

    public record Pending(UUID id, String email, Instant expiresAt, String delivery) {}
    public List<Pending> list(Authentication auth, UUID project) {
        projects.admin(auth, project);
        return invitations.findByProjectIdOrderByEmail(project).stream()
                .map(i -> new Pending(i.getId(), i.getEmail(), i.getExpiresAt(), i.getDelivery())).toList();
    }

    public void invite(Authentication auth, UUID project, String address) {
        var admin = projects.admin(auth, project);
        UUID channel = requireChannel(admin.getProject().getInvitationChannelId());
        String email = address.strip().toLowerCase(Locale.ROOT);
        if (!EmailAddress.valid(email)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email address");
        invitations.findByProjectIdAndEmail(project, email).ifPresentOrElse(existing -> {
            if (!existing.getExpiresAt().isAfter(Instant.now())) existing.queue(channel, admin.getAccount().getSubject(), admin.getAccount().getDisplayName());
        }, () -> invitations.save(new ProjectInvitation(project, admin.getProject().getName(), email, channel,
                admin.getAccount().getSubject(), admin.getAccount().getDisplayName())));
    }

    public void resend(Authentication auth, UUID project, UUID id) {
        var admin = projects.admin(auth, project);
        UUID channel = requireChannel(admin.getProject().getInvitationChannelId());
        pending(project, id).queue(channel, admin.getAccount().getSubject(), admin.getAccount().getDisplayName());
    }

    public void revoke(Authentication auth, UUID project, UUID id) {
        projects.admin(auth, project);
        invitations.delete(pending(project, id));
    }

    private ProjectInvitation pending(UUID project, UUID id) {
        return invitations.findByIdAndProjectId(id, project).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public UUID accept(Authentication auth, String token) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !Boolean.TRUE.equals(jwt.getToken().getClaims().get("email_verified")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A verified email is required");
        var account = projects.provision(auth);
        String digest = ProjectInvitation.digest(token);
        // Use the same project-then-invitation lock order as revoke and resend.
        var project = invitations.projectForToken(digest).flatMap(projectRows::lock)
                .orElseThrow(ProjectInvitations::unavailable);
        var invitation = invitations.findByTokenDigest(digest)
                .filter(i -> i.getExpiresAt().isAfter(Instant.now()) && i.getEmail().equalsIgnoreCase(jwt.getToken().getClaimAsString("email")))
                .orElseThrow(ProjectInvitations::unavailable);
        UUID id = invitation.getProjectId();
        if (!memberships.existsByProjectIdAndAccountSubject(id, account.getSubject()))
            memberships.save(new ProjectMembership(project, account, ProjectMembership.Role.valueOf(invitation.getRole())));
        invitations.delete(invitation);
        return id;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Invitation unavailable for this account");
    }
}
