package uk.appsthatmatter.koonj.fuse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class KoonjAgentClient {
    private static final String REGISTRATION = "koonj-agent";
    private static final String PRINCIPAL = "koonj-fuse";
    private final RestClient http;
    private final int timeout;
    private final Cache<UUID, Metadata> metadata = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    KoonjAgentClient(String server, String tokenUri, String clientId, String secret, int timeout, String projectId) {
        var registration = ClientRegistration.withRegistrationId(REGISTRATION)
                .clientId(clientId).clientSecret(secret).clientName(REGISTRATION)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .tokenUri(tokenUri).build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(requestFactory(Duration.ofSeconds(10)))
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(provider -> provider.accessTokenResponseClient(tokenClient)).build());

        var bearer = new OAuth2ClientHttpRequestInterceptor(request -> {
            // Serialize a refresh so simultaneous native callbacks share one token acquisition.
            synchronized (manager) {
                return manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                        .principal(PRINCIPAL).build());
            }
        });
        bearer.setClientRegistrationIdResolver(request -> REGISTRATION);
        bearer.setPrincipalResolver(request ->
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(PRINCIPAL, null));
        bearer.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(clients));

        var http = RestClient.builder().baseUrl(server)
                .requestFactory(requestFactory(Duration.ofSeconds(timeout + 25L)))
                .requestInterceptor(bearer);
        if (!projectId.isBlank()) http.defaultHeader("X-Koonj-Project", UUID.fromString(projectId.strip()).toString());
        this.http = http.build();
        this.timeout = timeout;
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    Metadata metadata(UUID id) {
        return metadata.get(id, fileId -> Objects.requireNonNull(http.get().uri("/api/v1/catalog/files/{id}", fileId)
                .retrieve().body(Metadata.class)));
    }

    RecallResult recall(UUID id) {
        var response = http.post().uri("/api/v1/transfers/recall/sync")
                .body(new RecallRequest(id, timeout)).retrieve().toEntity(RecallResult.class);
        var result = Objects.requireNonNull(response.getBody());
        if (response.getStatusCode().value() == 200 && "RECALLED".equals(result.status())) {
            metadata.invalidate(id);
        }
        return result;
    }

    record Metadata(UUID id, String relativePath, String symlinkTarget, long sizeBytes, Instant lastModifiedAt) {}
    record RecallRequest(UUID fileId, int timeoutSeconds) {}
    record RecallResult(String status, Instant estimatedReadyAt) {}
}
