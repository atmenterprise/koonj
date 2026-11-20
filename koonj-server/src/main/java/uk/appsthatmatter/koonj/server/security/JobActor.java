package uk.appsthatmatter.koonj.server.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class JobActor {
    private JobActor() {}

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
