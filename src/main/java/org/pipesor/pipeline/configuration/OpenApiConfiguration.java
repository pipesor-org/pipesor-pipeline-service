package org.pipesor.pipeline.configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
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
        };
    }
}
