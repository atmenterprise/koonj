package uk.appsthatmatter.koonj.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
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
                                   ObjectProvider<DaoAuthenticationProvider> basicProvider,
                                   UrlBasedCorsConfigurationSource corsSource) throws Exception {
        DaoAuthenticationProvider basic = basicProvider.getIfAvailable();
        var bearer = new BearerTokenAuthenticationEntryPoint();
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            bearer.commence(request, response, failure);
            if (basic != null) response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Koonj\"");
            writeError(mapper, response, HttpStatus.UNAUTHORIZED, "Sign in with a valid access token.");
        };
        AccessDeniedHandler forbidden = (request, response, failure) ->
                writeError(mapper, response, HttpStatus.FORBIDDEN, "Insufficient permissions for this action");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakAuthorities());

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        if (basic != null) {
            http.authenticationProvider(basic).httpBasic(config -> config.authenticationEntryPoint(unauthorized));
        }
        return http.build();
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
