package org.pipesor.pipeline.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.application.services.SnapshotRejected;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Local delivery via an operator-owned publication spool. The normal snapshot handler still owns admission. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pipeline.local-publications.directory")
public class LocalSnapshotConfiguration {
    @Bean
    SnapshotDelivery localSnapshotDelivery(ExecutionSnapshotService snapshots, Environment env) throws IOException {
        var directory = Path.of(env.getRequiredProperty("pipeline.local-publications.directory"));
        if (!"127.0.0.1".equals(env.getRequiredProperty("server.address"))
                || !directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalArgumentException("Local snapshot delivery requires loopback and a private outbox");
        }
        return new SnapshotDelivery(snapshots, directory,
                env.getRequiredProperty("pipeline.local-publications.repository"),
                env.getRequiredProperty("pipeline.local-publications.workflow"));
    }

    static final class SnapshotDelivery {
        private static final int LIMIT = 512 * 1024;
        private static final System.Logger LOG = System.getLogger(SnapshotDelivery.class.getName());
        private static final JsonMapper JSON = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();
        private final ExecutionSnapshotTransformer transform = new ExecutionSnapshotTransformer();
        private final Set<Path> processed = new HashSet<>();
        private final Set<Path> waitingForRegistration = new HashSet<>();
        private final ExecutionSnapshotService snapshots;
        private final Path directory;
        private final String repository;
        private final String workflow;

        SnapshotDelivery(ExecutionSnapshotService snapshots, Path directory, String repository, String workflow) {
            this.snapshots = snapshots;
            this.directory = directory;
            this.repository = repository;
            this.workflow = workflow;
        }

        @Scheduled(fixedDelay = 5000)
        public void deliver() {
            try (var files = Files.list(directory)) {
                files.filter(file -> file.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
                        .filter(file -> !processed.contains(file)).limit(64).forEach(this::apply);
            } catch (IOException unavailable) {
                LOG.log(System.Logger.Level.WARNING, "Local snapshot directory unavailable");
            }
        }

        private void apply(Path file) {
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(directory))
                        || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rw-------"))) throw new IllegalArgumentException();
            } catch (IOException | IllegalArgumentException invalid) {
                processed.add(file);
                LOG.log(System.Logger.Level.WARNING, "Local snapshot envelope rejected");
                return;
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IllegalArgumentException();
                var envelope = JSON.readTree(bytes);
                if (!"execution.snapshot".equals(envelope.at("/applicationProperties/eventType").asString())) {
                    processed.add(file);
                    return;
                }
                if (envelope.at("/applicationProperties/schemaMajor").asInt() != 1) throw new IllegalArgumentException();
                var command = transform.fromJson(Base64.getDecoder().decode(envelope.get("bodyBase64").asString()));
                var event = command.snapshot();
                if (!file.getFileName().toString().equals(event.eventId() + ".json")
                        || !event.eventId().toString().equals(envelope.get("messageId").asString())
                        || !event.correlationId().equals(envelope.get("correlationId").asString())
                        || !repository.equals(event.pipeline().githubRepositoryId())
                        || !workflow.equals(event.pipeline().githubWorkflowId())) throw new IllegalArgumentException();
                var disposition = snapshots.handle(command);
                processed.add(file);
                waitingForRegistration.remove(file);
                LOG.log(System.Logger.Level.INFO, "Local snapshot delivery {0}", disposition);
            } catch (SnapshotRejected rejected) {
                if ("UNKNOWN_WORKFLOW".equals(rejected.getMessage())) {
                    if (waitingForRegistration.add(file)) LOG.log(System.Logger.Level.WARNING, "Local snapshot waiting for workflow registration");
                } else {
                    processed.add(file);
                    LOG.log(System.Logger.Level.WARNING, "Local snapshot rejected {0}", rejected.getMessage());
                }
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException malformed) {
                processed.add(file);
                LOG.log(System.Logger.Level.WARNING, "Local snapshot envelope rejected");
            } catch (IOException | RuntimeException unavailable) {
                LOG.log(System.Logger.Level.WARNING, "Local snapshot delivery unavailable");
            }
        }
    }
}
