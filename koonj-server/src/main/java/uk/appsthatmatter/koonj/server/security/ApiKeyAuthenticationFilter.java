package uk.appsthatmatter.koonj.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyService keys;
    private final AuthenticationEntryPoint unauthorized;

    ApiKeyAuthenticationFilter(ApiKeyService keys, AuthenticationEntryPoint unauthorized) {
        this.keys = keys;
        this.unauthorized = unauthorized;
    }

    static boolean isMcp(HttpServletRequest request) {
        var path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        // MockMvc and some servlet mappings leave servletPath empty.
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/mcp") || path.startsWith("/api/v1/mcp/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isMcp(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = header.substring(7);
        } else if (header == null && "GET".equals(request.getMethod()) && request.getRequestURI().endsWith("/mcp/sse")) {
            token = request.getParameter("token");
        }

        var authentication = token != null && token.startsWith(ApiKeyService.PREFIX) ? keys.authenticate(token) : null;
        if (authentication == null) {
            unauthorized.commence(request, response, new OAuth2AuthenticationException("invalid_token"));
            return;
        }
        var principal = (ApiKeyService.Principal) authentication.getPrincipal();
        String requested = request.getHeader(ProjectScope.HEADER);
        if (requested != null && !principal.projectId().toString().equalsIgnoreCase(requested)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        request.setAttribute(ProjectScope.ATTRIBUTE, principal.projectId());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
