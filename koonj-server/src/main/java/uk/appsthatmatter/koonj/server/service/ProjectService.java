package uk.appsthatmatter.koonj.server.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.entity.Project;
import uk.appsthatmatter.koonj.server.entity.ProjectAccount;
import uk.appsthatmatter.koonj.server.entity.ProjectMembership;
import uk.appsthatmatter.koonj.server.repository.ProjectAccountRepository;
import uk.appsthatmatter.koonj.server.repository.ProjectMembershipRepository;
import uk.appsthatmatter.koonj.server.repository.ProjectRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import uk.appsthatmatter.koonj.notification.ProjectInvitationRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import uk.appsthatmatter.koonj.server.entity.ProjectMembership.Role;

@Service
public class ProjectService implements ApplicationRunner {
    private final ProjectRepository projects;
    private final ProjectAccountRepository accounts;
    private final ProjectMembershipRepository memberships;
    private final ProjectInvitationRepository invitations;

    public ProjectService(ProjectRepository projects, ProjectAccountRepository accounts, ProjectMembershipRepository memberships,
                          ProjectInvitationRepository invitations) {
        this.invitations = invitations;
        this.projects = projects;
        this.accounts = accounts;
        this.memberships = memberships;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (projects.existsById(ProjectScope.LEGACY)) return;
        try {
            projects.saveAndFlush(new Project(ProjectScope.LEGACY, "Default project"));
        } catch (DataIntegrityViolationException failure) {
            if (!projects.existsById(ProjectScope.LEGACY)) throw failure;
        }
    }

    public record ProjectView(UUID id, String name, Role role, UUID defaultProjectId) {
        static ProjectView from(ProjectMembership member) {
            var project = member.getProject();
            return new ProjectView(project.getId(), project.getName(), member.getRole(), member.getAccount().effectiveDefaultId());
        }
    }
    public record AccountView(String subject, String displayName, String email, Role role, boolean personalOwner) {}

    @Transactional
    public List<ProjectView> onboard(Authentication auth) {
        var account = provision(auth);
        return memberships.findByAccountSubjectOrderByProjectNameAscProjectIdAsc(account.getSubject())
                .stream().map(ProjectView::from).toList();
    }

    ProjectAccount provision(Authentication auth) {
        var account = register(auth);
        if (account.effectiveDefaultId() == null) {
            // Serialize generated-name selection across instances, including migrated accounts.
            projects.lock(ProjectScope.LEGACY).orElseThrow();
            var random = java.util.concurrent.ThreadLocalRandom.current();
            String[] colors = {"Amber", "Copper", "Silver", "Golden", "Indigo", "Scarlet"};
            String[] birds = {"Heron", "Crane", "Falcon", "Sparrow", "Kingfisher", "Kestrel"};
            String base = colors[random.nextInt(colors.length)] + " " + birds[random.nextInt(birds.length)] + " Workspace";
            String name = base;
            while (projects.existsByGeneratedName(name)) name = base + " " + UUID.randomUUID().toString().substring(0, 8);
            var project = projects.save(Project.personal(name));
            account.setDefaultProject(project);
            memberships.save(new ProjectMembership(project, account, Role.ADMIN));
        }
        return account;
    }

    @Transactional
    public UUID authorize(Authentication auth, UUID requested) {
        if (requested == null) return provision(auth).effectiveDefaultId();
        member(auth, requested);
        return requested;
    }

    ProjectMembership member(Authentication auth, UUID id) {
        return memberships.findByProjectIdAndAccountSubject(id, identity(auth)).orElseThrow(ProjectService::denied);
    }

    ProjectMembership admin(Authentication auth, UUID id) {
        projects.lock(id).orElseThrow(ProjectService::denied);
        var membership = member(auth, id);
        if (membership.getRole() != Role.ADMIN) throw denied();
        return membership;
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Project access denied");
    }

    @Transactional
    public ProjectView create(Authentication auth, String name) {
        var account = provision(auth);
        var project = projects.save(new Project(UUID.randomUUID(), name.strip()));
        return ProjectView.from(memberships.save(new ProjectMembership(project, account, Role.ADMIN)));
    }

    @Transactional
    public ProjectView rename(Authentication auth, UUID id, String name) {
        var membership = admin(auth, id);
        var project = membership.getProject();
        project.rename(name);
        invitations.renameProject(id, project.getName());
        return ProjectView.from(membership);
    }

    @Transactional
    public void delete(Authentication auth, UUID id) {
        projects.lock(ProjectScope.LEGACY).orElseThrow();
        var project = admin(auth, id).getProject();
        var members = memberships.findByProjectIdOrderByAccountDisplayNameAscAccountSubjectAsc(id);
        for (var member : members) {
            prepareRemoval(member);
            memberships.delete(member);
        }
        invitations.revokeProject(id);
        project.markDeleted();
    }

    private void prepareRemoval(ProjectMembership member) {
        UUID id = member.getProject().getId();
        var account = accounts.lock(member.getAccount().getSubject()).orElseThrow();
        var next = memberships.findByAccountSubjectOrderByProjectNameAscProjectIdAsc(account.getSubject()).stream()
                .map(m -> m.getProject().getId()).filter(other -> !other.equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Each member must have another project before losing access to this one"));
        if (id.equals(account.effectiveDefaultId())) {
            account.setDefaultProject(null);
            account.setFallbackProjectId(next);
        }
    }

    @Transactional(readOnly = true)
    public List<AccountView> members(Authentication auth, UUID id) {
        member(auth, id);
        return memberships.findByProjectIdOrderByAccountDisplayNameAscAccountSubjectAsc(id).stream().map(m -> {
            var account = m.getAccount();
            return new AccountView(account.getSubject(), account.getDisplayName(), account.getEmail(), m.getRole(),
                    account.getDefaultProject() != null && account.getDefaultProject().getId().equals(id));
        }).toList();
    }

    @Transactional
    public void remove(Authentication auth, UUID id, String subject) {
        projects.lock(ProjectScope.LEGACY).orElseThrow();
        admin(auth, id);
        memberships.findByProjectIdAndAccountSubject(id, subject).ifPresent(m -> {
            var personal = m.getAccount().getDefaultProject();
            if ((personal != null && personal.getId().equals(id)) ||
                    (m.getRole() == Role.ADMIN && memberships.countByProjectIdAndRole(id, Role.ADMIN) == 1))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This project requires its administrator");
            prepareRemoval(m);
            memberships.delete(m);
        });
    }

    private ProjectAccount register(Authentication auth) {
        String subject = identity(auth);
        ProjectAccount account = accounts.lock(subject).orElseGet(() -> {
            // Serialize first-time account inserts across API instances.
            projects.lock(ProjectScope.LEGACY).orElseThrow();
            return accounts.lock(subject).orElseGet(ProjectAccount::new);
        });
        account.setSubject(subject);
        String name = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("name") : null;
        String email = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("email") : null;
        String username = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("preferred_username") : auth.getName();
        account.setDisplayName(limit(name == null || name.isBlank() ? Objects.requireNonNullElse(username, auth.getName()) : name, 255));
        account.setEmail(email == null ? null : limit(email, 255));
        return accounts.saveAndFlush(account);
    }

    private static String limit(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max);
    }
    private static String identity(Authentication auth) { return (auth instanceof JwtAuthenticationToken ? "oidc:" : "basic:") + auth.getName(); }
}
