package org.pipesor.pipeline.configuration;

import java.util.List;
import org.pipesor.pipeline.execution.interfaces.rest.validation.OperationalReferenceCheckController;
import org.pipesor.pipeline.incident.interfaces.rest.DiagnosisEntitlementController;
import org.pipesor.pipeline.shared.security.PlatformAuthorities;
import org.pipesor.pipeline.shared.security.PlatformSecurityConfiguration.PlatformRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Pipeline's routes in the platform route catalog (platform identity contract v1, §2 and §3). */
@Configuration(proxyBeanMethods = false)
public class PipelineSecurityConfiguration {
    static final String SESSION_SCOPE = "Pipeline.Access";

    @Bean
    PlatformRoutes pipelineRoutes(@Value("${springdoc.api-docs.enabled:false}") boolean apiDocs) {
        return routes -> {
            if (apiDocs) {
                // OpenAPI is enabled only by the local profile (MVP HTTP API conventions).
                routes.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                        "/swagger-ui/**").permitAll();
            }
            routes.requestMatchers("/api/v1/**").access(PlatformAuthorities.session(SESSION_SCOPE));
            routes.requestMatchers(HttpMethod.POST, OperationalReferenceCheckController.PATH).access(AuthorizationManagers.anyOf(
                    PlatformAuthorities.workload("correlation", "Correlation.OperationalReferences.Check"),
                    PlatformAuthorities.workload("log-event", "Evidence.Check")));
            routes.requestMatchers(HttpMethod.POST, DiagnosisEntitlementController.PATH).access(
                    PlatformAuthorities.workloadForSubject("recommendation", "Recommendation.DiagnosisEntitlements.Resolve"));
        };
    }

    /** CORS for the dashboard origin only ({@code PIPESOR_DASHBOARD_ORIGIN}); no credentials mode. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${pipesor.dashboard-origin:}") String origin) {
        var source = new UrlBasedCorsConfigurationSource();
        if (origin.isBlank()) return source;
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
        cors.setExposedHeaders(List.of("X-Correlation-ID", "Location"));
        cors.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }
}
