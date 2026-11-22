package org.pipesor.pipeline.shared.security;

import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.pipesor.pipeline.shared.interfaces.rest.ApiExceptionHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;

import java.time.Clock;
import java.util.Collections;

/**
 * Shared composition of the platform identity contract: signing key, workload token issuance,
 * token verification, subject tokens and the stateless filter chain. Services contribute only their
 * routes. Authentication and authorization failures are {@code application/problem+json}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityConfiguration {

    /** Service-owned route rules, applied between the public routes and the final deny-all. */
    @FunctionalInterface
    public interface PlatformRoutes {
        void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry routes);
    }

    @Bean
    TransportPolicy transportPolicy(Environment environment) {
        return TransportPolicy.forProfiles(environment.getActiveProfiles());
    }

    @Bean
    SigningKey workloadSigningKey(PlatformSecurityProperties settings, TransportPolicy transport) {
        if (settings.signingKeyFile() != null) {
            if (!transport.loopbackHttpAllowed()) throw new IllegalStateException("File signing keys require the local profile");
            return new PemSigningKey(settings.signingKeyFile());
        }
        return new KeyVaultSigningKey(settings.signingKeyId(), new DefaultAzureCredentialBuilder().build());
    }

    @Bean
    WorkloadTokens workloadTokens(PlatformSecurityProperties settings, SigningKey workloadSigningKey) {
        return new WorkloadTokens(settings.workloadId(), settings.tenantId(), workloadSigningKey, Clock.systemUTC());
    }

    @Bean
    PlatformJwtDecoder platformJwtDecoder(PlatformSecurityProperties settings, TransportPolicy transport) {
        return new PlatformJwtDecoder(settings, transport);
    }

    @Bean
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, PlatformJwtDecoder platformJwtDecoder,
            PlatformRoutes routes) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            ApiExceptionHandler.writeSecurityProblem(response, request, HttpStatus.UNAUTHORIZED.value());
        };
        AccessDeniedHandler denied = (request, response, failure) ->
                ApiExceptionHandler.writeSecurityProblem(response, request, HttpStatus.FORBIDDEN.value());
        http.csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new UndisclosedEndpointsFilter(), org.springframework.security.web.session.DisableEncodeUrlFilter.class)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.requireExplicitSave(true))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .oauth2ResourceServer(server -> server.bearerTokenResolver(PlatformSecurityConfiguration::singleBearer).jwt(jwt -> jwt.decoder(platformJwtDecoder)
                        .jwtAuthenticationConverter(PlatformAuthorities.converter()))
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .addFilterAfter(new SubjectTokens(platformJwtDecoder, unauthorized), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, JwksController.PATH, "/actuator/health/liveness",
                            "/actuator/health/readiness").permitAll();
                    routes.configure(requests);
                    requests.anyRequest().denyAll();
                });
        return http.build();
    }

    /** Accepts exactly one Authorization header carrying one bearer token; never query or form tokens. */
    static String singleBearer(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.isEmpty()) return null;
        if (values.size() > 1 || !values.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)
                || !values.getFirst().substring(7).matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest("Exactly one bearer token is required"));
        }
        return values.getFirst().substring(7);
    }
}
