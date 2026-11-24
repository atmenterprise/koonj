package uk.appsthatmatter.koonj.server.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class JobActor {
    private JobActor() {}

    public static String displayName() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ApiKeyService.Principal key) return key.ownerName();
        if (!(authentication instanceof JwtAuthenticationToken token)) return null;
        String name = token.getToken().getClaimAsString("name");
        if (name != null && !name.isBlank()) return name.strip();
        String given = token.getToken().getClaimAsString("given_name");
        String family = token.getToken().getClaimAsString("family_name");
        String fullName = ((given == null ? "" : given.strip()) + " " + (family == null ? "" : family.strip())).strip();
        return fullName.isEmpty() ? null : fullName;
    }

    public static String subject() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return null;
        if (authentication.getPrincipal() instanceof ApiKeyService.Principal key) return key.ownerSubject();
        if (authentication instanceof JwtAuthenticationToken jwt) return "oidc:" + jwt.getToken().getSubject();
        return "basic:" + authentication.getName();
    }

    public static String verifiedEmail() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !Boolean.TRUE.equals(token.getToken().getClaims().get("email_verified"))) return null;
        Object email = token.getToken().getClaims().get("email");
        return email instanceof String value && value.length() <= 320 && !value.isBlank() ? value : null;
    }

    public static String current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            for (String claim : new String[]{"preferred_username", "email"}) {
                if (token.getToken().getClaims().get(claim) instanceof String value && !value.isBlank()) {
                    return value;
                }
            }
        }
        return authentication == null ? null : authentication.getName();
    }
}
