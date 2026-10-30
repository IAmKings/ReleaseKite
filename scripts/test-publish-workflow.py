#!/usr/bin/env python3
"""Exercise the actual Packages workflow with local curl/Gradle substitutes."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent
MODULES = {"releasekite-core": "jar", "releasekite": "aar", "releasekite-compose": "aar"}


class PublishWorkflowTest(unittest.TestCase):
    def run_workflow(self, responses=None, after=None, transport_error=False, gradle_exit=0,
                     coordinates="VERSION_NAME=0.1.0\nGROUP=com.github.IAmKings.ReleaseKite\n"):
        workflow = (ROOT / ".github/workflows/publish-github-packages.yml").read_text()
        start = workflow.index("        run: |", workflow.index("- name: Publish missing modules"))
        script = textwrap.dedent(workflow[start:].split("\n", 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "gradle.properties").write_text(coordinates)
            (path / "state.json").write_text(json.dumps(dict(
                responses=responses or {}, after=after or {},
                transport_error=transport_error, gradle_exit=gradle_exit)))
            curl = path / "curl"
            curl.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
p = pathlib.Path.cwd()
s = json.loads((p / "state.json").read_text())
if s["transport_error"]: sys.exit(28)
name = sys.argv[-1].rsplit("/", 1)[-1]
responses = s["after"] if (p / "published").exists() else s["responses"]
default = "200" if (p / "published").exists() else "404"
print(responses.get(name, default), end="")
''')
            gradle = path / "gradlew"
            gradle.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
p = pathlib.Path.cwd()
(p / "tasks.json").write_text(json.dumps(sys.argv[1:]))
(p / "published").touch()
sys.exit(json.loads((p / "state.json").read_text())["gradle_exit"])
''')
            curl.chmod(0o755)
            gradle.chmod(0o755)
            env = dict(os.environ, PATH=str(path) + os.pathsep + os.environ["PATH"],
                       GITHUB_TOKEN="test-placeholder", GITHUB_REPOSITORY="example/repo")
            result = subprocess.run(["bash", "-c", script], cwd=path, env=env,
                                    capture_output=True, text=True, timeout=15)
            tasks = json.loads((path / "tasks.json").read_text()) if (path / "tasks.json").exists() else []
            return result, [task for task in tasks if task.startswith(":")]

    def test_publish_all_missing_modules(self):
        result, tasks = self.run_workflow()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([f":{m}:publishReleasePublicationToGitHubPackagesRepository" for m in MODULES], tasks)

    def test_complete_version_is_verified_without_publish(self):
        responses = {f"{m}-0.1.0.{ext}": "200" for m, binary in MODULES.items() for ext in ("pom", binary)}
        result, tasks = self.run_workflow(responses)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], tasks)

    def test_publish_only_missing_modules_after_partial_success(self):
        result, tasks = self.run_workflow({"releasekite-core-0.1.0.pom": "200", "releasekite-core-0.1.0.jar": "200"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([f":{m}:publishReleasePublicationToGitHubPackagesRepository" for m in ("releasekite", "releasekite-compose")], tasks)

    def test_incomplete_module_aborts_before_any_upload(self):
        for module, binary in MODULES.items():
            for ext in ("pom", binary):
                with self.subTest(module=module, ext=ext):
                    result, tasks = self.run_workflow({f"{module}-0.1.0.{ext}": "200"})
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("publish a new version", result.stdout)
                    self.assertEqual([], tasks)

    def test_probe_http_errors_are_not_treated_as_missing(self):
        for code in ("401", "403", "429", "500"):
            with self.subTest(code=code):
                result, tasks = self.run_workflow({"releasekite-core-0.1.0.pom": code})
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Cannot determine package state", result.stdout)
                self.assertEqual([], tasks)

    def test_transport_failure_aborts_without_publish(self):
        result, tasks = self.run_workflow(transport_error=True)
        self.assertEqual(28, result.returncode)
        self.assertEqual([], tasks)

    def test_post_publish_missing_binary_fails_verification(self):
        result, tasks = self.run_workflow(after={"releasekite-core-0.1.0.jar": "404"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Published artifact unavailable", result.stdout)
        self.assertEqual(3, len(tasks))

    def test_gradle_failure_is_propagated(self):
        result, _ = self.run_workflow(gradle_exit=17)
        self.assertEqual(17, result.returncode)

    def test_empty_coordinates_abort_before_publish(self):
        result, tasks = self.run_workflow(coordinates="VERSION_NAME=\nGROUP=example\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing publication coordinates", result.stdout)
        self.assertEqual([], tasks)


if __name__ == "__main__":
    unittest.main()
