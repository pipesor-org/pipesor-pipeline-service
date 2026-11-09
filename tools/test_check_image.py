import subprocess
import unittest
from unittest.mock import patch

from tools.check_image import main


class ImageCheckTests(unittest.TestCase):
    @patch("tools.check_image.subprocess.run")
    @patch("tools.check_image.docker")
    def test_timeout_removes_the_container_whose_client_was_killed(self, docker, run):
        docker.return_value = "10001:10001"
        run.side_effect = [subprocess.TimeoutExpired("docker", 60)] + [subprocess.CompletedProcess([], 0)] * 4
        with self.assertRaises(subprocess.TimeoutExpired):
            main("local-image")
        commands = [call.args[0] for call in run.call_args_list]
        timed_out_name = commands[0][commands[0].index("--name") + 1]
        self.assertIn(["docker", "rm", "--force", timed_out_name], commands[1:])


if __name__ == "__main__":
    unittest.main()
