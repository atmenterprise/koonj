package uk.appsthatmatter.koonj.server.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.entity.UserApiKey;
import uk.appsthatmatter.koonj.server.repository.ProjectMembershipRepository;
import uk.appsthatmatter.koonj.server.repository.UserApiKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ApiKeyService {
    public static final String PREFIX = "kj_live_";
    private static final Pattern TOKEN = Pattern.compile(PREFIX + "[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final UserApiKeyRepository keys;
    private final ProjectMembershipRepository memberships;

    public ApiKeyService(UserApiKeyRepository keys, ProjectMembershipRepository memberships) {
        this.keys = keys;
        this.memberships = memberships;
    }

    public record Principal(UUID keyId, String ownerSubject, String ownerName, UUID projectId)
            implements AuthenticatedPrincipal {
        @Override public String getName() { return ownerName; }
    }

    public record TokenView(UUID id, String name, String tokenPrefix, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {
        static TokenView from(UserApiKey key) {
            return new TokenView(key.getId(), key.getName(), key.getTokenPrefix(), key.getCreatedAt(), key.getLastUsedAt(),
                    key.getExpiresAt());
        }
    }

    public record CreatedToken(TokenView metadata, String token) {}

    public List<TokenView> list() {
        return keys.activeForOwner(JobActor.subject(), ProjectScope.id(), Instant.now()).stream().map(TokenView::from).toList();
    }

    @Transactional
    public CreatedToken create(Authentication authentication, String name, Instant expiresAt) {
        Instant now = Instant.now();
        if (expiresAt != null && !expiresAt.isAfter(now)) throw new IllegalArgumentException("Expiration must be in the future");
        var roles = KoonjRoles.ALL.stream()
                .filter(role -> authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_" + role)))
                .collect(Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access is pending");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String ownerName = Objects.requireNonNullElse(JobActor.displayName(), JobActor.current());
        if (ownerName.length() > 255) ownerName = ownerName.substring(0,
                Character.isHighSurrogate(ownerName.charAt(254)) ? 254 : 255);
        var key = keys.save(new UserApiKey(name.strip(), hash(token), token.substring(0, 16),
                JobActor.subject(), ownerName, ProjectScope.id(), roles, now, expiresAt));
        return new CreatedToken(TokenView.from(key), token);
    }

    @Transactional
    public void revoke(UUID id) {
        if (keys.revoke(id, JobActor.subject(), ProjectScope.id()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token was not found");
        }
    }

    @Transactional
    public UsernamePasswordAuthenticationToken authenticate(String token) {
        if (!TOKEN.matcher(token).matches()) return null;
        Instant now = Instant.now();
        var key = keys.findByTokenHash(hash(token)).orElse(null);
        if (key == null || key.isRevoked() || (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now))) return null;
        var principal = new Principal(key.getId(), key.getOwnerSubject(), key.getOwnerName(), key.getProjectId());
        if (!hasMembership(principal)) return null;
        // Coalesce writes from clients that send several messages in a handshake or tool burst.
        if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minusSeconds(30))) {
            keys.recordUse(key.getId(), now);
        }
        var authorities = key.getRoles().stream().filter(KoonjRoles.ALL::contains)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    public boolean isActive(Principal principal) {
        return keys.isActive(principal.keyId(), Instant.now()) && hasMembership(principal);
    }

    private boolean hasMembership(Principal principal) {
        return memberships.existsByProjectIdAndAccountSubject(principal.projectId(), principal.ownerSubject());
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
