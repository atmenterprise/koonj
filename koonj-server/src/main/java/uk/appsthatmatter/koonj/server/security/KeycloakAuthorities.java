package uk.appsthatmatter.koonj.server.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class KeycloakAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        addRoles(jwt.getClaims().get("realm_access"), authorities);
        if (jwt.getClaims().get("resource_access") instanceof Map<?, ?> resources) {
            addRoles(resources.get("koonj-server"), authorities);
        }
        return authorities;
    }

    private void addRoles(Object access, Set<GrantedAuthority> authorities) {
        if (access instanceof Map<?, ?> section && section.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && KoonjRoles.ALL.contains(name)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + name));
                }
            }
        }
    }
}
