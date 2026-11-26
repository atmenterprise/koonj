package uk.appsthatmatter.koonj.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import uk.appsthatmatter.koonj.server.dto.ErrorResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper,
                                   ObjectProvider<DaoAuthenticationProvider> basicProvider, ApiKeyService keys,
                                   UrlBasedCorsConfigurationSource corsSource) throws Exception {
        DaoAuthenticationProvider basic = basicProvider.getIfAvailable();
        var bearer = new BearerTokenAuthenticationEntryPoint();
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            bearer.commence(request, response, failure);
            if (basic != null) response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Koonj\"");
            writeError(mapper, response, HttpStatus.UNAUTHORIZED, "Sign in with a valid access token.");
        };
        AccessDeniedHandler forbidden = (request, response, failure) -> {
            if (failure instanceof CsrfException && ApiKeyAuthenticationFilter.isMcp(request)) {
                unauthorized.commence(request, response, new OAuth2AuthenticationException("invalid_token"));
                return;
            }
            writeError(mapper, response, HttpStatus.FORBIDDEN, "Insufficient permissions for this action");
        };
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakAuthorities());

        var bearerTokens = new DefaultBearerTokenResolver();
        http.addFilterBefore(new ApiKeyAuthenticationFilter(keys, unauthorized), BearerTokenAuthenticationFilter.class)
                // Browsers can attach cached Basic credentials to cross-site requests.
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> basic != null
                        && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && isBasicAuthorization(request.getHeader(HttpHeaders.AUTHORIZATION))))
                .cors(cors -> cors.configurationSource(corsSource))
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.ASYNC
                                && ApiKeyAuthenticationFilter.isMcp(request)
                                && request.getAttribute(ProjectScope.ATTRIBUTE) != null).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                // Spring uses this resolver for CSRF and JWT authentication. An MCP key is
                // visible to the CSRF filter, then handled by the API key filter before JWT authentication.
                .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(request ->
                                ApiKeyAuthenticationFilter.isMcp(request) && request.getAttribute(ProjectScope.ATTRIBUTE) != null
                                        ? null : bearerTokens.resolve(request))
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        if (basic != null) {
            http.authenticationProvider(basic).httpBasic(config -> config.authenticationEntryPoint(unauthorized));
        }
        return http.build();
    }

    private static boolean isBasicAuthorization(String authorization) {
        return authorization != null && StringUtils.startsWithIgnoreCase(authorization.trim(), "Basic");
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfiguration(@Value("${koonj.security.allowed-origins}") String allowed) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(allowed.split(",")).map(String::strip).filter(origin -> !origin.isEmpty()).toList());
        if (cors.getAllowedOrigins().stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("CORS requires explicit origins");
        }
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", ProjectScope.HEADER));
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }

    @Bean
    @ConditionalOnProperty(name = "koonj.security.basic-auth.enabled", havingValue = "true")
    DaoAuthenticationProvider basicAuthentication(
            @Value("${koonj.security.basic-auth.username}") String username,
            @Value("${koonj.security.basic-auth.password}") String password,
            @Value("${koonj.security.basic-auth.role}") String role) {
        if (username.isBlank() || password.isBlank() || !KoonjRoles.ALL.contains(role)) {
            throw new IllegalArgumentException("Basic authentication requires a username, password and valid Koonj role");
        }
        var encoder = new BCryptPasswordEncoder();
        var user = User.withUsername(username).password(encoder.encode(password)).roles(role).build();
        var provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(user));
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
