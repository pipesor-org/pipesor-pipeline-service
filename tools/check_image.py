"""Check the packaged image using disposable local Docker resources, never Azure."""

import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from uuid import uuid4


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], text=True, capture_output=True, check=True, **kwargs).stdout.strip()


def main(image):
    name = "pipeline-check-" + uuid4().hex[:12]
    database, app = name + "-db", name + "-app"
    missing = name + "-missing"
    try:
        assert docker("image", "inspect", image, "--format", "{{.Config.User}}") == "10001:10001"
        failed = subprocess.run(["docker", "run", "--rm", "--name", missing, "--network", "none", image],
                                capture_output=True, text=True, timeout=60)
        assert failed.returncode != 0 and "Failed to configure a DataSource" in failed.stdout + failed.stderr
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
        # This shared-schema fixture is test setup, outside service migration ownership.
        with open("src/test/resources/db/shared-audit.sql") as fixture:
            docker("exec", "-i", database, "psql", "-v", "ON_ERROR_STOP=1", "-U", "pipeline", "-d", "pipeline", input=fixture.read())
        docker("run", "--detach", "--name", app, "--network", name, "--publish", "127.0.0.1::8080",
               "--env", f"SPRING_DATASOURCE_URL=jdbc:postgresql://{database}:5432/pipeline",
               "--env", "SPRING_DATASOURCE_USERNAME=pipeline", "--env", "SPRING_DATASOURCE_PASSWORD=local-check-only",
               image, "--spring.profiles.active=local", "--springdoc.api-docs.enabled=false", "--springdoc.swagger-ui.enabled=false")
        address = docker("port", app, "8080/tcp")
        for attempt in range(60):
            try:
                with urlopen(f"http://{address}/api/v1/pipelines", timeout=2):
                    raise RuntimeError("Unauthenticated business request unexpectedly succeeded")
            except HTTPError as error:
                assert error.code == 401, f"Unexpected HTTP status: {error.code}"
                print("Image checks passed: nonroot Java, missing-DB rejection, PostgreSQL startup and HTTP 401")
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
