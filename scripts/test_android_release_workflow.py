#!/usr/bin/env python3
"""Contract tests for .github/workflows/android-release.yml

Verifies triggers, permissions, exact publication guard conditions,
security preservation guards, and executes the extracted version derivation
shell script across exact ref agreements, unknown prefixes, mismatches,
empty inputs, and malformed tags.
"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "android-release.yml"

ALLOWED_PUBLISH_CONDITIONS = {
    "startsWith(github.ref, 'refs/tags/')",
}


def load_workflow_text() -> str:
    if not WORKFLOW_PATH.is_file():
        raise FileNotFoundError(f"Workflow file not found at {WORKFLOW_PATH}")
    return WORKFLOW_PATH.read_text(encoding="utf-8")


def get_publish_job_if_condition(workflow_content: str) -> str:
    """Extracts the 'if:' condition of the 'publish' job.
    Uses PyYAML if available; falls back to an indent-aware parser without external dependencies.
    """
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(workflow_content)
        if isinstance(data, dict):
            jobs = data.get("jobs", {})
            publish_job = jobs.get("publish", {})
            cond = publish_job.get("if")
            if cond is not None:
                return str(cond).strip()
    except Exception:
        pass

    # Indent-aware fallback parser
    lines = workflow_content.splitlines()
    in_jobs = False
    in_publish = False
    publish_indent = None
    for line in lines:
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())
        if stripped == "jobs:":
            in_jobs = True
            continue
        if in_jobs:
            if in_publish:
                if stripped and indent <= publish_indent:
                    break
                if stripped.startswith("if:"):
                    raw_cond = stripped[len("if:") :].strip()
                    if (raw_cond.startswith('"') and raw_cond.endswith('"')) or (
                        raw_cond.startswith("'") and raw_cond.endswith("'")
                    ):
                        return raw_cond[1:-1].strip()
                    return raw_cond
            elif stripped == "publish:" or stripped.startswith("publish:"):
                in_publish = True
                publish_indent = indent

    raise ValueError("Could not find publish job 'if:' condition in workflow")


def validate_publish_job_condition(condition: str) -> None:
    if condition not in ALLOWED_PUBLISH_CONDITIONS:
        raise AssertionError(
            f"Unsafe or unauthorized publish condition: '{condition}'. "
            f"Must be strictly one of: {sorted(ALLOWED_PUBLISH_CONDITIONS)}"
        )


def extract_version_derivation_script(workflow_content: str) -> str:
    lines = workflow_content.splitlines()
    in_step = False
    in_run = False
    run_lines: list[str] = []
    run_indent: int | None = None
    script_indent: int | None = None

    for line in lines:
        if "- name: Derive the version" in line:
            in_step = True
            continue
        if in_step:
            if in_run:
                if line.strip():
                    current_indent = len(line) - len(line.lstrip())
                    if current_indent <= run_indent:
                        break
                    if script_indent is None:
                        script_indent = current_indent
                    run_lines.append(line[script_indent:])
                else:
                    run_lines.append("")
            elif line.strip().startswith("run: |"):
                in_run = True
                run_indent = len(line) - len(line.lstrip())

    if not run_lines:
        raise ValueError("Could not extract version derivation script from workflow")
    return "\n".join(run_lines).strip()


def run_derivation_script(
    script: str, env_vars: dict[str, str]
) -> tuple[int, dict[str, str], str, str]:
    with tempfile.NamedTemporaryFile(mode="w+", delete=False) as env_file:
        env_file_path = env_file.name

    try:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "GITHUB_ENV": env_file_path,
        }
        env.update(env_vars)

        proc = subprocess.run(
            ["bash", "-c", script],
            env=env,
            capture_output=True,
            text=True,
        )

        output_env: dict[str, str] = {}
        with open(env_file_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line:
                    k, v = line.split("=", 1)
                    output_env[k] = v

        return proc.returncode, output_env, proc.stdout, proc.stderr
    finally:
        if os.path.exists(env_file_path):
            os.remove(env_file_path)


class TestWorkflowTriggersAndPermissions(unittest.TestCase):
    def setUp(self) -> None:
        self.content = load_workflow_text()

    def test_triggers_include_tag_route_and_exact_repair_branch_only(self) -> None:
        on_match = re.search(r"on:\s*\n\s*push:\s*\n((?:\s+.*\n)+)", self.content)
        self.assertIsNotNone(on_match, "Expected 'on: push:' block in workflow")
        push_block = on_match.group(1)

        # Tags route must be unchanged ('v*')
        self.assertIn("tags:", push_block)
        tags_match = re.search(r"tags:\s*\n\s*-\s*'v\*'", push_block)
        self.assertIsNotNone(tags_match, "Expected tags: - 'v*' under push")

        # Branches route must be exact branch feature/android-v0.3.0-ux-repair only
        self.assertIn("branches:", push_block)
        branches_match = re.search(
            r"branches:\s*\n\s*-\s*'feature/android-v0\.3\.0-ux-repair'", push_block
        )
        self.assertIsNotNone(
            branches_match,
            "Expected branches: - 'feature/android-v0.3.0-ux-repair' under push",
        )

        # No other branch patterns allowed
        branch_lines = [
            line.strip()
            for line in re.findall(r"branches:\s*\n((?:\s*-\s*.*\n?)+)", push_block)[
                0
            ].splitlines()
            if line.strip().startswith("-")
        ]
        self.assertEqual(
            branch_lines,
            ["- 'feature/android-v0.3.0-ux-repair'"],
            f"Unexpected branch triggers: {branch_lines}",
        )

    def test_least_privilege_permissions(self) -> None:
        # Root permissions must be contents: read
        root_perm = re.search(
            r"^permissions:\s*\n\s*contents:\s*read", self.content, re.MULTILINE
        )
        self.assertIsNotNone(
            root_perm, "Root workflow permissions must be 'contents: read'"
        )

        # Build job must not escalate contents permission to write
        build_job_match = re.search(
            r"\n  build:\s*\n(.*?)(?=\n  \w+:|$)", self.content, re.DOTALL
        )
        self.assertIsNotNone(build_job_match, "build job must exist")
        self.assertNotIn(
            "contents: write",
            build_job_match.group(1),
            "build job must not have write permissions",
        )

        # Publish job must have contents: write
        publish_job_match = re.search(
            r"\n  publish:\s*\n(.*?)(?=\n  \w+:|$)", self.content, re.DOTALL
        )
        self.assertIsNotNone(publish_job_match, "publish job must exist")
        self.assertIn(
            "contents: write",
            publish_job_match.group(1),
            "publish job must have contents: write",
        )

    def test_publish_job_exact_tag_only_condition(self) -> None:
        cond = get_publish_job_if_condition(self.content)
        validate_publish_job_condition(cond)

    def test_unsafe_publish_guard_mutations_are_rejected(self) -> None:
        unsafe_mutations = [
            "startsWith(github.ref, 'refs/tags/') || true",
            "startsWith(github.ref, 'refs/tags/') || github.ref_type == 'branch'",
            "true",
            "always()",
            "startsWith(github.ref, 'refs/heads/')",
            "${{ startsWith(github.ref, 'refs/tags/') || true }}",
            "startsWith(github.ref, 'refs/tags/') || false || true",
        ]
        for unsafe in unsafe_mutations:
            with self.subTest(mutation=unsafe):
                mutated = re.sub(
                    r"(\n\s*publish:\s*\n.*?\n\s*if:).*",
                    rf"\1 {unsafe}",
                    self.content,
                    flags=re.DOTALL,
                )
                cond = get_publish_job_if_condition(mutated)
                with self.assertRaises(
                    AssertionError,
                    msg=f"Validator should have rejected unsafe condition: {unsafe}",
                ):
                    validate_publish_job_condition(cond)

        # Missing condition must also raise error
        no_if_content = re.sub(
            r"(\n\s*publish:\s*\n.*?)(\n\s*if:.*)",
            r"\1",
            self.content,
        )
        with self.assertRaises((ValueError, AssertionError)):
            cond = get_publish_job_if_condition(no_if_content)
            validate_publish_job_condition(cond)

    def test_security_guards_and_cleanup_preserved(self) -> None:
        # Key cert check preserved
        self.assertIn("EXPECTED_CERT_SHA256:", self.content)
        self.assertIn(
            "EE:0E:40:4B:21:10:52:B3:11:AE:0E:07:30:60:6E:9C:7D:55:54:31:54:63:D0:2F:9E:F3:3C:95:73:C4:31:F6",
            self.content,
        )
        self.assertIn("apksigner", self.content)

        # Signed requirement preserved
        self.assertIn("-PrequireSigned=true", self.content)

        # Keystore removal always executed
        self.assertIn("Remove the decoded keystore", self.content)
        step_match = re.search(
            r"- name: Remove the decoded keystore.*?(?=\n\s*(?:- name:|publish:|\Z))",
            self.content,
            re.DOTALL,
        )
        self.assertIsNotNone(step_match, "Keystore cleanup step must be present")
        step_body = step_match.group(0)
        self.assertIn(
            "if: always()", step_body, "Keystore cleanup must specify 'if: always()'"
        )
        self.assertIn(
            'rm -f "$RUNNER_TEMP/trindade-release.jks"',
            step_body,
            "Keystore cleanup must rm keystore file",
        )

        # Upload artifact preserved
        self.assertIn("uses: actions/upload-artifact@v4", self.content)
        self.assertIn("name: android-release", self.content)


class TestVersionDerivationExecution(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        workflow_content = load_workflow_text()
        cls.script = extract_version_derivation_script(workflow_content)

    def test_valid_repair_branch_derives_0_3_0_and_301(self) -> None:
        code, env_out, stdout, stderr = run_derivation_script(
            self.script,
            {
                "GITHUB_REF": "refs/heads/feature/android-v0.3.0-ux-repair",
                "GITHUB_REF_NAME": "feature/android-v0.3.0-ux-repair",
                "GITHUB_REF_TYPE": "branch",
            },
        )
        self.assertEqual(code, 0, f"Script failed with stderr: {stderr}")
        self.assertEqual(env_out.get("VERSION_NAME"), "0.3.0")
        self.assertEqual(env_out.get("VERSION_CODE"), "301")
        self.assertEqual(env_out.get("APK_NAME"), "trindade-0.3.0.apk")

    def test_valid_tag_derivation_formula(self) -> None:
        cases = [
            ("v0.3.0", "0.3.0", "300", "trindade-0.3.0.apk"),
            ("v1.2.3", "1.2.3", "10203", "trindade-1.2.3.apk"),
            ("v0.0.1", "0.0.1", "1", "trindade-0.0.1.apk"),
            ("v2.0.0", "2.0.0", "20000", "trindade-2.0.0.apk"),
            ("v0.99.99", "0.99.99", "9999", "trindade-0.99.99.apk"),
        ]
        for tag, expected_name, expected_code, expected_apk in cases:
            with self.subTest(tag=tag):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    {
                        "GITHUB_REF": f"refs/tags/{tag}",
                        "GITHUB_REF_NAME": tag,
                        "GITHUB_REF_TYPE": "tag",
                    },
                )
                self.assertEqual(code, 0, f"Tag {tag} failed: {stderr}")
                self.assertEqual(env_out.get("VERSION_NAME"), expected_name)
                self.assertEqual(env_out.get("VERSION_CODE"), expected_code)
                self.assertEqual(env_out.get("APK_NAME"), expected_apk)

    def test_empty_or_missing_ref_env_fails_closed(self) -> None:
        cases = [
            {},
            {"GITHUB_REF": "", "GITHUB_REF_NAME": "v0.3.0", "GITHUB_REF_TYPE": "tag"},
            {
                "GITHUB_REF": "refs/tags/v0.3.0",
                "GITHUB_REF_NAME": "",
                "GITHUB_REF_TYPE": "tag",
            },
            {
                "GITHUB_REF": "refs/tags/v0.3.0",
                "GITHUB_REF_NAME": "v0.3.0",
                "GITHUB_REF_TYPE": "",
            },
            {"GITHUB_REF_NAME": "feature/android-v0.3.0-ux-repair"},
            {"GITHUB_REF_NAME": "v0.3.0"},
            {"GITHUB_REF": "refs/heads/feature/android-v0.3.0-ux-repair"},
        ]
        for env_case in cases:
            with self.subTest(case=env_case):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    env_case,
                )
                self.assertNotEqual(
                    code,
                    0,
                    f"Empty/missing ref env {env_case} should fail closed, got success",
                )
                self.assertNotIn("VERSION_NAME", env_out)
                self.assertNotIn("VERSION_CODE", env_out)

    def test_unknown_ref_prefixes_fail_closed(self) -> None:
        unknown_prefixes = [
            ("refs/pull/123/merge", "123/merge", "pull_request"),
            ("refs/pull/123/head", "123/head", "branch"),
            ("refs/notes/commits", "commits", "tag"),
            ("refs/remotes/origin/main", "origin/main", "branch"),
            ("refs/changes/01/1", "01/1", "branch"),
            ("custom/ref/test", "test", "branch"),
            ("refs/tags/sub/v0.3.0", "sub/v0.3.0", "tag"),
            ("heads/feature/android-v0.3.0-ux-repair", "feature/android-v0.3.0-ux-repair", "branch"),
        ]
        for ref, ref_name, ref_type in unknown_prefixes:
            with self.subTest(ref=ref):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    {
                        "GITHUB_REF": ref,
                        "GITHUB_REF_NAME": ref_name,
                        "GITHUB_REF_TYPE": ref_type,
                    },
                )
                self.assertNotEqual(
                    code, 0, f"Unknown ref prefix '{ref}' should fail closed"
                )
                self.assertNotIn("VERSION_NAME", env_out)
                self.assertNotIn("VERSION_CODE", env_out)

    def test_mismatched_refs_fail_closed(self) -> None:
        mismatch_cases = [
            # Repair branch ref with tag type
            (
                "refs/heads/feature/android-v0.3.0-ux-repair",
                "feature/android-v0.3.0-ux-repair",
                "tag",
            ),
            # Tag ref with branch type
            ("refs/tags/v0.3.0", "v0.3.0", "branch"),
            # Repair branch name with refs/tags/ prefix
            (
                "refs/tags/feature/android-v0.3.0-ux-repair",
                "feature/android-v0.3.0-ux-repair",
                "branch",
            ),
            # Tag name with refs/heads/ prefix
            ("refs/heads/v0.3.0", "v0.3.0", "tag"),
            # GITHUB_REF and GITHUB_REF_NAME mismatch for branch
            (
                "refs/heads/other-branch",
                "feature/android-v0.3.0-ux-repair",
                "branch",
            ),
            # GITHUB_REF and GITHUB_REF_NAME mismatch for tag
            ("refs/tags/v0.3.1", "v0.3.0", "tag"),
        ]
        for ref, ref_name, ref_type in mismatch_cases:
            with self.subTest(ref=ref, ref_name=ref_name, ref_type=ref_type):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    {
                        "GITHUB_REF": ref,
                        "GITHUB_REF_NAME": ref_name,
                        "GITHUB_REF_TYPE": ref_type,
                    },
                )
                self.assertNotEqual(
                    code, 0, f"Mismatched ref combo should fail closed: {ref}, {ref_name}, {ref_type}"
                )
                self.assertNotIn("VERSION_NAME", env_out)
                self.assertNotIn("VERSION_CODE", env_out)

    def test_rejected_branches(self) -> None:
        rejected_branches = [
            "main",
            "master",
            "feature/android-v0.3.0",
            "feature/android-v0.3.0-ux-repair-extra",
            "repair",
            "hotfix/0.3.0",
        ]
        for branch in rejected_branches:
            with self.subTest(branch=branch):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    {
                        "GITHUB_REF": f"refs/heads/{branch}",
                        "GITHUB_REF_NAME": branch,
                        "GITHUB_REF_TYPE": "branch",
                    },
                )
                self.assertNotEqual(
                    code, 0, f"Branch '{branch}' should have been rejected"
                )
                self.assertNotIn("VERSION_NAME", env_out)
                self.assertNotIn("VERSION_CODE", env_out)

    def test_rejected_tags(self) -> None:
        rejected_tags = [
            "v1.2",  # missing patch
            "v1.2.3.4",  # 4 components
            "0.3.0",  # missing leading v
            "v1.100.0",  # minor > 99
            "v1.0.100",  # patch > 99
            "vabc",  # non-numeric
            "",  # empty
        ]
        for tag in rejected_tags:
            with self.subTest(tag=tag):
                code, env_out, stdout, stderr = run_derivation_script(
                    self.script,
                    {
                        "GITHUB_REF": f"refs/tags/{tag}",
                        "GITHUB_REF_NAME": tag,
                        "GITHUB_REF_TYPE": "tag",
                    },
                )
                self.assertNotEqual(code, 0, f"Tag '{tag}' should have been rejected")
                self.assertNotIn("VERSION_NAME", env_out)
                self.assertNotIn("VERSION_CODE", env_out)


if __name__ == "__main__":
    unittest.main()
