package org.pipesor.pipeline.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Runtime manifest: startup fails when a local-only setting appears under the azure profile. */
@Configuration(proxyBeanMethods = false)
@Profile("azure")
public class AzureRuntimeGuard {
    @Bean
    InitializingBean rejectLocalOnlySettings(Environment environment) {
        return () -> {
            if (environment.matchesProfiles("local")) {
                throw new IllegalStateException("The local and azure profiles are exclusive");
            }
            for (String name : new String[]{"PIPESOR_DATABASE_PASSWORD", "PIPESOR_SERVICE_BUS_CONNECTION_STRING",
                    "PIPESOR_SIGNING_KEY_FILE"}) {
                String value = environment.getProperty(name);
                if (value != null && !value.isBlank()) throw new IllegalStateException(name + " is local-only");
            }
        };
    }
}
