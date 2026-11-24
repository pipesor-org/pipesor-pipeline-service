package org.pipesor.pipeline.configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.interfaces.rest.transform.IncidentFilterParameters;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Pipeline Service", version = "v1"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfiguration {
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer apiErrors() {
        return api -> {
            var schema = new ObjectSchema()
                    .addProperty("status", new IntegerSchema())
                    .addProperty("detail", new StringSchema())
                    .addProperty("code", new StringSchema())
                    .addProperty("correlationId", new StringSchema());
            var media = new MediaType().schema(schema);
            api.getPaths().forEach((path, item) -> {
                if (path.startsWith("/api/v1/")) {
                    item.readOperations().forEach(operation -> {
                        Map.of("400", "Malformed request", "401", "Invalid authentication",
                                "403", "Pipeline capability required", "404", "Resource absent",
                                "409", "State or verification conflict", "500", "Service failure", "503", "GitHub verification unavailable").forEach((code, description) ->
                            operation.getResponses().addApiResponse(code, new ApiResponse()
                                    .description(description).content(new Content()
                                            .addMediaType("application/problem+json", media))));
                    });
                }
            });
            var incidents = api.getPaths().get("/api/v1/incidents");
            if (incidents != null && incidents.getGet() != null) incidentFilters(incidents.getGet());
            var summary = api.getPaths().get("/api/v1/incidents/summary");
            if (summary != null && summary.getGet() != null) incidentFilters(summary.getGet());
        };
    }

    /** Repeatable queue filters (ADR-027), read from the raw query string by {@code IncidentFilterParameters}. */
    private static void incidentFilters(Operation operation) {
        operation.addParametersItem(repeated(IncidentFilterParameters.PIPELINE_ID, "Pipeline UUID",
                new StringSchema().format("uuid")));
        operation.addParametersItem(repeated(IncidentFilterParameters.REPOSITORY_ID, "GitHub numeric repository id",
                new StringSchema().pattern("^[1-9][0-9]{0,19}$")));
        operation.addParametersItem(repeated(IncidentFilterParameters.SEVERITY, "UNCLASSIFIED selects incidents without severity",
                values(IncidentFilter.SeverityFilter.values())));
        operation.addParametersItem(repeated(IncidentFilterParameters.STAGE, "Incident stage", values(PipelineStage.values())));
        operation.addParametersItem(repeated(IncidentFilterParameters.STATUS,
                "OPEN, IN_REMEDIATION, VERIFICATION with state=OPEN; RESOLVED with state=RESOLVED", values(IncidentStatus.values())));
        operation.addParametersItem(repeated(IncidentFilterParameters.DIAGNOSIS,
                "Diagnosis review status; ABSENT selects incidents without a projected diagnosis", values(IncidentFilter.DiagnosisFilter.values())));
        operation.addParametersItem(new QueryParameter().name(IncidentFilterParameters.Q).required(false)
                .description("INC-123 or 123 matches displayNumber exactly (from one digit); other text (2-100 characters) is a "
                        + "case-insensitive partial match on the repository full name or the workflow name")
                .schema(new StringSchema().minLength(1).maxLength(100)));
        for (var name : new String[] {IncidentFilterParameters.RESOLVED_FROM, IncidentFilterParameters.RESOLVED_TO}) {
            operation.addParametersItem(new QueryParameter().name(name).required(false)
                    .description("RFC 3339 instant, inclusive bound of resolvedAt; only with state=RESOLVED")
                    .schema(new StringSchema().format("date-time")));
        }
        operation.getResponses().get("400").setDescription(
                "INVALID_REQUEST (state, pageSize), INVALID_FILTER (filter values) or INVALID_PAGE_TOKEN");
    }

    private static Parameter repeated(String name, String description, Schema<?> item) {
        return new QueryParameter().name(name).required(false).description(description + "; repeatable, values are OR-ed")
                .style(Parameter.StyleEnum.FORM).explode(true).schema(new ArraySchema().items(item).maxItems(100));
    }

    private static StringSchema values(Enum<?>[] constants) {
        var schema = new StringSchema();
        for (var constant : constants) schema.addEnumItem(constant.name());
        return schema;
    }
}
