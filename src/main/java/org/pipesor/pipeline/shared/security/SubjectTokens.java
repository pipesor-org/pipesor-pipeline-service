package org.pipesor.pipeline.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Subject tokens of the platform identity contract (§4). A workload acting for a user sends its own
 * workload token in {@code Authorization} and the user's unmodified session token in
 * {@value #HEADER}. The session token is verified with the session rules for this audience and is
 * never accepted alone: the header is rejected unless the request is authenticated as a workload.
 * Routes that need a subject require it with {@link PlatformAuthorities#workloadForSubject}.
 */
public final class SubjectTokens extends OncePerRequestFilter {
    public static final String HEADER = "X-Pipesor-Subject-Token";
    static final String ATTRIBUTE = SubjectTokens.class.getName() + ".subject";

    private final PlatformJwtDecoder decoder;
    private final AuthenticationEntryPoint unauthorized;

    public SubjectTokens(PlatformJwtDecoder decoder, AuthenticationEntryPoint unauthorized) {
        this.decoder = Objects.requireNonNull(decoder);
        this.unauthorized = Objects.requireNonNull(unauthorized);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (values.size() != 1 || !workload(SecurityContextHolder.getContext().getAuthentication())) {
            unauthorized.commence(request, response, new InvalidBearerTokenException("Subject token not accepted"));
            return;
        }
        final Jwt subject;
        try {
            subject = decoder.decodeSession(values.getFirst());
        } catch (JwtException invalid) {
            unauthorized.commence(request, response, new InvalidBearerTokenException("Invalid subject token"));
            return;
        }
        request.setAttribute(ATTRIBUTE, subject);
        chain.doFilter(request, response);
    }

    /** The verified subject token of this request, when a workload sent one. */
    public static Optional<Jwt> subject(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof Jwt jwt ? Optional.of(jwt) : Optional.empty();
    }

    /**
     * The verified human session behind the current request: the caller's own session token, or the
     * subject token a workload sent on the user's behalf. Empty outside an HTTP request.
     */
    public static Optional<Jwt> currentSession() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && PlatformIds.SESSION_ISSUER.equals(token.getToken().getClaimAsString("iss"))) {
            return Optional.of(token.getToken());
        }
        var request = RequestContextHolder.getRequestAttributes();
        if (request == null || !workload(authentication)) return Optional.empty();
        return request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Jwt jwt
                ? Optional.of(jwt) : Optional.empty();
    }

    private static boolean workload(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token && authentication.isAuthenticated()
                && token.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().startsWith("workload:"));
    }
}
