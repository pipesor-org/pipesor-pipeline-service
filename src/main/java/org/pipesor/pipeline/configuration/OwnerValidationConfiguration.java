package org.pipesor.pipeline.configuration;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.infrastructure.access.HttpIngestionScopeAuthority;
import org.pipesor.pipeline.execution.interfaces.rest.validation.OwnerValidationServlet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pipeline.owner-validation.enabled", havingValue = "true")
@EnableConfigurationProperties(OwnerValidationProperties.class)
public class OwnerValidationConfiguration {
    public record MachineDecoder(JwtDecoder decoder) {}

    @Bean
    @ConditionalOnMissingBean(MachineDecoder.class)
    MachineDecoder ownerMachineDecoder(OwnerValidationProperties settings) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString()).build();
        decoder.setJwtValidator(identityValidator(settings,Clock.systemUTC()));
        return new MachineDecoder(decoder);
    }

    public static OAuth2TokenValidator<Jwt> identityValidator(OwnerValidationProperties settings, Clock clock) {
        var times = new JwtTimestampValidator(settings.clockSkew());
        times.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(times, new JwtIssuerValidator(settings.issuer().toString()), jwt -> {
            boolean valid;
            try {
                valid = "2.0".equals(jwt.getClaimAsString("ver")) && "app".equals(jwt.getClaimAsString("idtyp"))
                        && jwt.getClaim("scp") == null && jwt.getAudience().contains(settings.audience())
                        && settings.tenant().equals(jwt.getClaimAsString("tid"))
                        && canonicalUuid(jwt.getClaimAsString("oid")) && canonicalUuid(jwt.getClaimAsString("azp"))
                        && (jwt.getClaim("appid") == null || jwt.getClaimAsString("appid").equals(jwt.getClaimAsString("azp")))
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                        && !jwt.getIssuedAt().isAfter(clock.instant().plus(settings.clockSkew()));
            } catch (RuntimeException invalid) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token","Invalid workload identity",null));
        });
    }

    @Bean @Order(0)
    SecurityFilterChain ownerValidationSecurity(HttpSecurity http, OwnerValidationProperties settings, MachineDecoder machine) throws Exception {
        return http.securityMatcher(OwnerValidationServlet.PATH)
                .csrf(csrf -> csrf.disable()).logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().access((authentication, context) -> {
                    boolean allowed = false;
                    if (authentication.get() instanceof JwtAuthenticationToken token) {
                        try {
                            var jwt = token.getToken();
                            var roles = jwt.getClaimAsStringList("roles");
                            allowed = "POST".equals(context.getRequest().getMethod())
                                    && settings.callerPrincipalId().equals(jwt.getClaimAsString("oid"))
                                    && settings.callerClientId().equals(jwt.getClaimAsString("azp"))
                                    && roles != null && roles.contains(settings.role());
                        } catch (RuntimeException invalid) { allowed = false; }
                    }
                    return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                }))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(machine.decoder()))
                        .authenticationEntryPoint((request,response,error) -> OwnerValidationServlet.problem(response,401))
                        .accessDeniedHandler((request,response,error) -> OwnerValidationServlet.problem(response,403)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request,response,error) -> OwnerValidationServlet.problem(response,401))
                        .accessDeniedHandler((request,response,error) -> OwnerValidationServlet.problem(response,403)))
                .build();
    }

    @Bean
    HttpIngestionScopeAuthority ingestionScopeAuthority(OwnerValidationProperties settings) {
        var credential = new ManagedIdentityCredentialBuilder().build();
        return new HttpIngestionScopeAuthority(HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(),settings.ingestionUri(),
                remaining -> credential.getToken(new TokenRequestContext().addScopes(settings.ingestionScope())).block(remaining),
                settings.maxResponseBytes(),Clock.systemUTC());
    }

    @Bean
    ServletRegistrationBean<OwnerValidationServlet> ownerValidationServlet(OwnerValidationProperties settings,
            @Lazy OperationalReferenceLookup lookup, HttpIngestionScopeAuthority authority, Clock clock) {
        var servlet = new OwnerValidationServlet(settings, lookup, authority, clock);
        var registration = new ServletRegistrationBean<>(servlet, OwnerValidationServlet.PATH);
        registration.setAsyncSupported(true);
        return registration;
    }

    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value) && !value.equals(new UUID(0,0).toString()); }
        catch (RuntimeException invalid) { return false; }
    }
}
