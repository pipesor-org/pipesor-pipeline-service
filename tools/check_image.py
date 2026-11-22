"""Check the packaged image using disposable local Docker resources, never Azure."""

import io
import subprocess
import sys
import tarfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from uuid import uuid4


def docker(*args, **kwargs):
    text = not isinstance(kwargs.get("input"), bytes)
    output = subprocess.run(["docker", *args], text=text, capture_output=True, check=True, **kwargs).stdout
    return output.strip() if text else output


def signing_key_archive():
    """A disposable PKCS#8 key owned by the image user with mode 0600, as a tar stream for docker cp."""
    pem = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                         capture_output=True, check=True).stdout
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        entry = tarfile.TarInfo("pipeline.pem")
        entry.size, entry.mode, entry.uid, entry.gid = len(pem), 0o600, 10001, 10001
        archive.addfile(entry, io.BytesIO(pem))
    return buffer.getvalue()


def main(image):
    name = "pipeline-check-" + uuid4().hex[:12]
    database, app = name + "-db", name + "-app"
    missing = name + "-missing"
    try:
        assert docker("image", "inspect", image, "--format", "{{.Config.User}}") == "10001:10001"
        failed = subprocess.run(["docker", "run", "--rm", "--name", missing, "--network", "none", image],
                                capture_output=True, text=True, timeout=60)
        assert failed.returncode != 0, "Startup without runtime configuration must fail"
        docker("network", "create", name)
        docker("run", "--detach", "--name", database, "--network", name,
               "--env", "POSTGRES_DB=pipeline", "--env", "POSTGRES_USER=pipeline",
               "--env", "POSTGRES_PASSWORD=local-check-only",
               "postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3")
        for attempt in range(30):
            ready = subprocess.run(["docker", "exec", database, "pg_isready", "-U", "pipeline"], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")
        # Synthetic local platform settings; peers are never contacted by this check.
        environment = {
            "SPRING_PROFILES_ACTIVE": "local",
            "PIPESOR_DATABASE_URL": f"jdbc:postgresql://{database}:5432/pipeline",
            "PIPESOR_DATABASE_USERNAME": "pipeline", "PIPESOR_DATABASE_PASSWORD": "local-check-only",
            "PIPESOR_TENANT_ID": "24c96be0-50f3-4064-b5e4-643088141ae8", "PIPESOR_WORKLOAD_ID": "pipeline",
            "PIPESOR_SIGNING_KEY_FILE": "/tmp/pipeline.pem", "PIPESOR_SECURITY_AUDIENCE": "urn:pipesor:api:pipeline",
            "PIPESOR_SECURITY_SESSION_JWKSURI": "http://127.0.0.1:8081/.well-known/jwks.json",
            "PIPESOR_ENDPOINTS_INGESTION": "http://127.0.0.1:8081", "PIPELINE_MESSAGING_ENABLED": "false",
        }
        arguments = ["create", "--name", app, "--network", name, "--publish", "127.0.0.1::8080"]
        for key, value in environment.items():
            arguments += ["--env", f"{key}={value}"]
        docker(*arguments, image)
        docker("cp", "-a", "-", app + ":/tmp", input=signing_key_archive())
        docker("start", app)
        address = docker("port", app, "8080/tcp")
        for attempt in range(60):
            try:
                with urlopen(f"http://{address}/api/v1/pipelines", timeout=2):
                    raise RuntimeError("Unauthenticated business request unexpectedly succeeded")
            except HTTPError as error:
                assert error.code == 401, f"Unexpected HTTP status: {error.code}"
                with urlopen(f"http://{address}/actuator/health/readiness", timeout=2) as ready:
                    assert ready.status == 200, f"Readiness returned {ready.status}"
                print("Image checks passed: nonroot Java, missing-config rejection, migrations, readiness and HTTP 401")
                return
            except (URLError, ConnectionError, TimeoutError):
                if docker("inspect", app, "--format", "{{.State.Running}}") != "true":
                    raise RuntimeError("Packaged service exited during startup") from None
                time.sleep(1)
        raise RuntimeError("Packaged service did not become reachable")
    finally:
        for container in (app, database, missing):
            subprocess.run(["docker", "rm", "--force", container], capture_output=True)
        subprocess.run(["docker", "network", "rm", name], capture_output=True)


if __name__ == "__main__":
    main(sys.argv[1])
