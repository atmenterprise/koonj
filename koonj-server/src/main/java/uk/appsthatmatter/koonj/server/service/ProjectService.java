package uk.appsthatmatter.koonj.server.service;

import org.springframework.beans.factory.annotation.Value;
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
import uk.appsthatmatter.koonj.server.security.KoonjRoles;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService implements ApplicationRunner {
    private final ProjectRepository projects;
    private final ProjectAccountRepository accounts;
    private final ProjectMembershipRepository memberships;
    private final Set<String> legacySubjects;

    public ProjectService(ProjectRepository projects, ProjectAccountRepository accounts, ProjectMembershipRepository memberships,
                          @Value("${koonj.projects.legacy-subjects:}") String legacySubjects) {
        this.projects = projects;
        this.accounts = accounts;
        this.memberships = memberships;
        this.legacySubjects = Arrays.stream(legacySubjects.split(",")).map(String::strip)
                .filter(subject -> !subject.isEmpty()).collect(Collectors.toUnmodifiableSet());
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

    public record ProjectView(UUID id, String name) {
        static ProjectView from(Project project) { return new ProjectView(project.getId(), project.getName()); }
    }
    public record AccountView(String subject, String displayName, String email) {
        static AccountView from(ProjectAccount account) {
            return new AccountView(account.getSubject(), account.getDisplayName(), account.getEmail());
        }
    }

    @Transactional
    public List<ProjectView> onboard(Authentication auth) {
        ProjectAccount account = register(auth);
        var existing = memberships.findByAccountSubjectOrderByProjectNameAscProjectIdAsc(account.getSubject());
        if (!existing.isEmpty()) return existing.stream().map(member -> ProjectView.from(member.getProject())).toList();
        boolean legacy = isAdministrator(auth) || !(auth instanceof JwtAuthenticationToken) || legacySubjects.contains(auth.getName());
        Project project = legacy ? project(ProjectScope.LEGACY) : projects.save(
                new Project(UUID.randomUUID(), limit("Personal project · " + account.getDisplayName(), 100)));
        memberships.save(new ProjectMembership(project, account));
        return List.of(ProjectView.from(project));
    }

    @Transactional
    public UUID authorize(Authentication auth, UUID requested) {
        if (!hasWorkspaceRole(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access is pending");
        String subject = identity(auth);
        if (requested != null && memberships.existsByProjectIdAndAccountSubject(requested, subject)) return requested;
        if (requested == null) {
            var choices = memberships.findByAccountSubjectOrderByProjectNameAscProjectIdAsc(subject);
            if (!choices.isEmpty()) return choices.stream().map(member -> member.getProject().getId())
                    .filter(ProjectScope.LEGACY::equals).findFirst().orElseGet(() -> choices.getFirst().getProject().getId());
            return onboard(auth).getFirst().id();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Choose a project you belong to");
    }

    public List<ProjectView> all() { return projects.findAllByOrderByNameAscIdAsc().stream().map(ProjectView::from).toList(); }
    public List<AccountView> accounts() { return accounts.findAllByOrderByDisplayNameAscSubjectAsc().stream().map(AccountView::from).toList(); }

    @Transactional
    public ProjectView create(Authentication auth, String name) {
        var account = register(auth);
        var project = projects.save(new Project(UUID.randomUUID(), name.strip()));
        memberships.save(new ProjectMembership(project, account));
        return ProjectView.from(project);
    }

    @Transactional(readOnly = true)
    public List<AccountView> members(UUID id) {
        project(id);
        return memberships.findByProjectIdOrderByAccountDisplayNameAscAccountSubjectAsc(id).stream()
                .map(member -> AccountView.from(member.getAccount())).toList();
    }

    @Transactional
    public void assign(UUID id, String subject) {
        var account = account(subject);
        var project = project(id);
        if (!memberships.existsByProjectIdAndAccountSubject(id, subject)) memberships.save(new ProjectMembership(project, account));
    }

    @Transactional
    public void remove(UUID id, String subject) {
        account(subject);
        project(id);
        if (!memberships.existsByProjectIdAndAccountSubject(id, subject)) return;
        if (memberships.countByAccountSubject(subject) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assign another project before removing this membership");
        }
        memberships.deleteByProjectIdAndAccountSubject(id, subject);
    }

    private Project project(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project was not found"));
    }

    private ProjectAccount account(String subject) {
        return accounts.lock(subject).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found"));
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
    private static boolean hasWorkspaceRole(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(role -> KoonjRoles.ALL.stream()
                .anyMatch(allowed -> ("ROLE_" + allowed).equals(role.getAuthority())));
    }
    private static boolean isAdministrator(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(role -> ("ROLE_" + KoonjRoles.ADMIN).equals(role.getAuthority()));
    }
}
