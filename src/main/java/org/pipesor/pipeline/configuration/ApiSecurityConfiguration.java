package org.pipesor.pipeline.configuration;

import java.util.Arrays;
import java.util.UUID;
import org.pipesor.pipeline.shared.interfaces.rest.ApiExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class ApiSecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, exception) ->
                                ApiExceptionHandler.writeSecurityProblem(response, request, 401))
                        .accessDeniedHandler((request, response, exception) ->
                                ApiExceptionHandler.writeSecurityProblem(response, request, 403)))
                .build();
    }

    @Bean
    JwtDecoder apiJwtDecoder(@Value("${pipeline.security.issuer:}") String issuer,
            @Value("${pipeline.security.tenant:}") String tenant,
            @Value("${pipeline.security.audience:}") String audience,
            @Value("${pipeline.security.scope:}") String scope) {
        if (issuer.isBlank() && tenant.isBlank() && audience.isBlank() && scope.isBlank()) {
            return token -> { throw new BadJwtException("API identity is not configured"); };
        }
        if (!issuer.startsWith("https://") || !canonicalUuid(tenant) || audience.isBlank() || scope.isBlank()) {
            throw new IllegalArgumentException("Configure HTTPS issuer, tenant UUID, audience and delegated scope together");
        }
        return new SupplierJwtDecoder(() -> {
            var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                    identityValidator(tenant, audience, scope)));
            return decoder;
        });
    }

    public static OAuth2TokenValidator<Jwt> identityValidator(String tenant, String audience, String scope) {
        return jwt -> {
            var scopes = jwt.getClaimAsString("scp");
            boolean valid = jwt.getExpiresAt() != null && jwt.getAudience().contains(audience)
                    && tenant.equalsIgnoreCase(jwt.getClaimAsString("tid")) && canonicalUuid(jwt.getClaimAsString("oid"))
                    && scopes != null && Arrays.asList(scopes.split(" ")).contains(scope);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required API identity claims are missing or invalid", null));
        };
    }

    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
