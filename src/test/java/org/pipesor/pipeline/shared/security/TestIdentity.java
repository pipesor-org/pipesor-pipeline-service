package org.pipesor.pipeline.shared.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test-only platform identities: locally generated RSA keys for the session issuer and every trusted
 * caller, a PEM signing key for this workload, and signed tokens following the identity contract v1.
 * Only key discovery is replaced; every claim rule of {@link PlatformJwtDecoder} still applies.
 */
public final class TestIdentity {
    public static final UUID TENANT = UUID.fromString("24c96be0-50f3-4064-b5e4-643088141ae8");
    public static final long GITHUB_USER = 87343021L;
    private static final KeyPair SESSION = rsa();
    private static final Map<String, KeyPair> WORKLOADS = Map.of(
            "correlation", rsa(), "log-event", rsa(), "recommendation", rsa());
    private static final Path SIGNING_KEY = signingKey();

    private TestIdentity() {}

    /** Platform settings for the pipeline workload under the {@code local} profile. */
    public static void register(DynamicPropertyRegistry registry) {
        properties().forEach((name, value) -> registry.add(name, () -> value));
    }

    public static Map<String, String> properties() {
        var properties = new java.util.LinkedHashMap<String, String>();
        properties.put("pipesor.workload-id", "pipeline");
        properties.put("pipesor.tenant-id", TENANT.toString());
        properties.put("pipesor.signing-key-file", SIGNING_KEY.toString());
        properties.put("pipesor.security.audience", "urn:pipesor:api:pipeline");
        properties.put("pipesor.security.session.jwks-uri", "http://127.0.0.1:9/session");
        String[][] trusted = {
                {"correlation", "Correlation.OperationalReferences.Check"},
                {"log-event", "Evidence.Check"},
                {"recommendation", "Recommendation.DiagnosisEntitlements.Resolve,Recommendation.DiagnosisSummaries.Write"}};
        for (int index = 0; index < trusted.length; index++) {
            properties.put("pipesor.security.trusted-workloads[" + index + "].id", trusted[index][0]);
            properties.put("pipesor.security.trusted-workloads[" + index + "].jwks-uri", "http://127.0.0.1:9/" + trusted[index][0]);
            properties.put("pipesor.security.trusted-workloads[" + index + "].roles", trusted[index][1]);
        }
        return properties;
    }

    /** Resolves the generated public keys instead of fetching JWKS documents. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Keys {
        @Bean @Primary
        PlatformJwtDecoder testPlatformJwtDecoder(PlatformSecurityProperties settings, TransportPolicy transport) {
            return new PlatformJwtDecoder(settings, transport, uri -> {
                String name = uri.getPath().substring(1);
                KeyPair pair = "session".equals(name) ? SESSION : WORKLOADS.get(name);
                return NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build();
            });
        }
    }

    public static String actor(long githubUserId) { return TENANT + ":" + PlatformIds.human(githubUserId); }

    public static String session(long githubUserId) { return session(githubUserId, claims -> { }); }

    /** A session token for this tenant; the customizer may break any claim. */
    public static String session(long githubUserId, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        String human = PlatformIds.human(githubUserId).toString();
        var claims = new JWTClaimsSet.Builder().issuer(PlatformIds.SESSION_ISSUER)
                .audience(List.of("urn:pipesor:api:ingestion", "urn:pipesor:api:pipeline", "urn:pipesor:api:log-event",
                        "urn:pipesor:api:recommendation"))
                .subject(human).claim("oid", human).claim("azp", PlatformIds.DASHBOARD_CLIENT.toString())
                .claim("tid", TENANT.toString())
                .claim("scp", "GitHub.Self Pipeline.Access Evidence.Read Diagnosis.Read Diagnosis.Review")
                .claim("github_user_id", Long.toString(githubUserId)).claim("ver", "2.0")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(1800)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        return sign(SESSION, claims.build());
    }

    /** A token signed with an untrusted key for an otherwise valid session. */
    public static String forgedSession(long githubUserId) {
        try {
            var jwt = SignedJWT.parse(session(githubUserId));
            return sign(rsa(), jwt.getJWTClaimsSet());
        } catch (java.text.ParseException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    /** A workload token issued by {@code caller} for the pipeline audience with one role. */
    public static String workload(String caller, String role) {
        return new WorkloadTokens(caller, TENANT, key(WORKLOADS.get(caller)), Clock.systemUTC()).issue("pipeline", role).value();
    }

    public static Map<String, Object> claims(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().toJSONObject();
        } catch (java.text.ParseException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static String sign(KeyPair pair, JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(pair.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static SigningKey key(KeyPair pair) {
        return new SigningKey() {
            @Override public RSAPublicKey publicKey() { return (RSAPublicKey) pair.getPublic(); }
            @Override public byte[] sign(byte[] input) {
                try {
                    var signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(input);
                    return signature.sign();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
    }

    private static Path signingKey() {
        try {
            var directory = Files.createTempDirectory("pipeline-test-identity");
            var file = directory.resolve("pipeline.pem");
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(rsa().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(file, pem);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            file.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return file;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static KeyPair rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
