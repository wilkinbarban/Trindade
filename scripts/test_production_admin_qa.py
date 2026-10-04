#!/usr/bin/env python3
"""Test suite for Production Admin QA script (scripts/verify_production_admin_qa.py).

Validates:
- Default dry-run mode and refusal to execute without --execute-production flag
- HTTPS origin guard and redirect refusal
- Unique namespace isolation and 0600 journal storage
- Strict prohibition of drivers / time-slots writes
- Secrets suppression in stdout, stderr, and error envelopes
- Prohibition of automatic POST retries on uncertain failure
- Verification via GET before DELETE and no foreign data deletion
- Full scenario execution with fake HTTP transport (admin & worker actions, 403 matrix, 401 revocation)
- Reverse cleanup (tasks -> category -> vehicle -> worker -> logout) on success and mid-run error
- All findings 1-7 (fail-closed category deletion, strict write allowlist, full UUID namespace,
  harmless worker probe, fail-closed cleanup & post-cleanup verification, fixed error codes).
"""

import io
import json
import os
import socket
import stat
import sys
import tempfile
import unittest
import unittest.mock
import uuid
from pathlib import Path

# Ensure repo root and scripts/ directory are on path
REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = Path(__file__).resolve().parent
for p in (str(REPO_ROOT), str(SCRIPTS_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)

from verify_production_admin_qa import (  # type: ignore[import-not-found]
    ALLOWED_ORIGIN,
    FakeHTTPTransport,
    ProductionQARunner,
    QASecurityError,
    main,
    mask_secrets,
)


class BaseIsolatedQATestCase(unittest.TestCase):
    """Base class providing isolated TemporaryDirectory for journal and auth configs.

    Guarantees no test writes into real ~/.config/trindade/qa-runs or touches real auth/network.
    """

    def setUp(self):
        super().setUp()
        self._temp_dir = tempfile.TemporaryDirectory()
        self.journal_dir = self._temp_dir.name
        self.config_path = os.path.join(self.journal_dir, "test-auth.json")

    def tearDown(self):
        self._temp_dir.cleanup()
        super().tearDown()

    def make_runner(self, **kwargs) -> ProductionQARunner:
        kwargs.setdefault("journal_dir", self.journal_dir)
        kwargs.setdefault("config_path", self.config_path)
        return ProductionQARunner(**kwargs)


class TestOriginAndSecurityGuards(BaseIsolatedQATestCase):
    """Verifies HTTPS origin guard, redirect refusal, and forbidden mutation endpoints."""

    def test_origin_constant(self):
        self.assertEqual(ALLOWED_ORIGIN, "https://trindademasas.duckdns.org")

    def test_refuse_invalid_origin(self):
        with self.assertRaises(QASecurityError):
            runner = self.make_runner(origin="https://malicious-site.com")
            runner.validate_origin()

    def test_refuse_http_origin(self):
        with self.assertRaises(QASecurityError):
            runner = self.make_runner(origin="http://trindademasas.duckdns.org")
            runner.validate_origin()

    def test_refuse_drivers_mutation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/drivers", body={"name": "test"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/drivers/1", body={"name": "test"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/drivers/1")

    def test_refuse_time_slots_mutation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PUT", "/api/admin/time-slots", body={"time_slots": ["08:00"]})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/time-slots", body={"time_slots": ["08:00"]})

    def test_refuse_reports_and_loading_mutation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/reports", body={})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/loading/schedules", body={})

    def test_refuse_original_account_mutation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/auth/profile", body={"display_name": "New"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/auth/change-password", body={"new_password": "New"})

    def test_redirect_refusal(self):
        transport = FakeHTTPTransport()
        transport.register_response("GET", "/api/test-redirect", status=302, headers={"Location": "https://trindademasas.duckdns.org/other"})
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("GET", "/api/test-redirect")


class TestDryRunAndExecutionFlags(BaseIsolatedQATestCase):
    """Verifies default dry-run mode and refusal to make live network calls without flag."""

    def test_dry_run_by_default(self):
        runner = self.make_runner(execute_production=False)
        self.assertTrue(runner.is_dry_run)
        plan = runner.get_dry_run_plan()
        self.assertIn("DRY-RUN", plan["mode"])
        self.assertFalse(plan["live_calls_permitted"])

    def test_no_live_calls_without_execute_flag(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(execute_production=False, transport=transport)
        result = runner.run()
        self.assertEqual(result["status"], "dry_run_completed")
        self.assertEqual(len(transport.history), 0)

    def test_main_cli_defaults_to_dry_run(self):
        captured_stdout = io.StringIO()
        original_stdout = sys.stdout
        try:
            sys.stdout = captured_stdout
            ret = main(["--journal-dir", self.journal_dir])
            self.assertEqual(ret, 0)
        finally:
            sys.stdout = original_stdout

        output = captured_stdout.getvalue()
        self.assertIn("DRY-RUN (default)", output)
        self.assertIn("no network calls were made", output)


class TestSecretSuppression(BaseIsolatedQATestCase):
    """Verifies passwords and tokens are never printed or leaked in output/errors."""

    def test_mask_secrets_in_text(self):
        sensitive_text = "Bearer eyJhbGciOiJIUzI1NiJ9.secret.sig password=SuperSecretPassword123! token=abc123xyz"
        masked = mask_secrets(sensitive_text)
        self.assertNotIn("SuperSecretPassword123!", masked)
        self.assertNotIn("eyJhbGciOiJIUzI1NiJ9.secret.sig", masked)
        self.assertIn("[REDACTED]", masked)

    def test_error_envelope_sanitization(self):
        runner = self.make_runner()
        err_msg = runner.sanitize_error_message("Failed with token eyJ123456 and password SuperPass999")
        self.assertNotIn("eyJ123456", err_msg)
        self.assertNotIn("SuperPass999", err_msg)


class TestNamespaceAndJournaling(BaseIsolatedQATestCase):
    """Verifies unique namespace generation and 0600 journal file permissions."""

    def test_unique_namespace_and_journal_mode(self):
        runner = self.make_runner()
        self.assertTrue(runner.nonce)
        self.assertTrue(len(runner.nonce) >= 8)

        runner.journal_record_resource("category", 42, f"qa_cat_{runner.nonce}")
        journal_path = runner.get_journal_path()
        self.assertTrue(os.path.exists(journal_path))

        # Verify file mode 0600 (owner read/write only)
        file_mode = stat.S_IMODE(os.stat(journal_path).st_mode)
        self.assertEqual(file_mode, 0o600)

        # Verify dir mode 0700
        dir_mode = stat.S_IMODE(os.stat(self.journal_dir).st_mode)
        self.assertEqual(dir_mode & 0o077, 0)


class TestUncertainPostNoRetry(BaseIsolatedQATestCase):
    """Verifies uncertain POST does not retry blindly and instead uses GET detection for cleanup."""

    def test_no_blind_post_retry(self):
        transport = FakeHTTPTransport()
        # Simulate network timeout / 500 on POST
        transport.register_error("POST", "/api/admin/categories", Exception("Connection reset"))
        runner = self.make_runner(transport=transport, execute_production=True)

        with self.assertRaises(Exception):
            runner.create_qa_category("admin_token_xyz")

        # Must have only attempted POST once, never blindly retried
        post_attempts = [req for req in transport.history if req["method"] == "POST" and req["path"] == "/api/admin/categories"]
        self.assertEqual(len(post_attempts), 1)

    def test_uncertain_post_discovered_via_get_during_cleanup(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="orphan99",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = "admin_mock_token_orphan99"

        # Simulate that a category was created on server, but client timed out
        transport.dynamic_state["categories"].append({
            "id": 999,
            "name_pt": "qa_cat_orphan99",
            "parent_category_id": None,
        })
        transport.register_response("DELETE", "/api/admin/categories/999", 200, {"success": True})

        # Run reverse cleanup: it must detect the category via GET and delete it
        runner.execute_reverse_cleanup()

        self.assertIn("category", runner.journal["cleaned_up"])
        delete_calls = [r for r in transport.history if r["method"] == "DELETE" and r["path"] == "/api/admin/categories/999"]
        self.assertEqual(len(delete_calls), 1)

    def test_residual_recorded_if_foreign_reference_blocks_delete(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="blocked77",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = "admin_mock_token_blocked77"
        runner.category_id = 888

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 888, "name_pt": "qa_cat_blocked77"}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        # Simulate backend 400 constraint error on DELETE
        transport.register_response("DELETE", "/api/admin/categories/888", 400, {
            "error": "Não é possível excluir a categoria pois possui registros relacionados."
        })

        try:
            runner.execute_reverse_cleanup()
        except Exception:
            pass

        # Category delete failed with 400 -> residual must be recorded without deleting foreign data
        self.assertTrue(any(res["type"] == "category" and res["id"] == 888 for res in runner.journal["residuals"]))


class TestPostCreationMutationLatch(BaseIsolatedQATestCase):
    """Unverified creation stops writes without converting obligations to authority."""

    def test_failed_proof_or_durability_stops_mutations(self):
        from unittest.mock import patch

        for failure in ("get", "registration", "obligation"):
            with self.subTest(failure=failure):
                transport = FakeHTTPTransport()
                runner = self.make_runner(transport=transport)
                path = "/api/admin/categories"
                payload = {"name_pt": f"qa_cat_{runner.nonce}"}
                transport.register_response("POST", path, 201, {"category": {"id": 105}})
                transport.register_response("GET", path, 200, {"categories": [{
                    "id": 105, "name_pt": payload["name_pt"], "parent_category_id": None,
                }]})
                if failure != "registration":
                    transport.register_response("GET", path, 500, {"error": "unavailable"})
                with patch.object(runner, "sync_journal", wraps=runner.sync_journal) as sync:
                    if failure != "get":
                        sync.side_effect = OSError("password=synthetic-secret")
                    with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
                        runner.guarded_request("POST", path, body=payload)
                obligation = {"entity_type": "categories", "id": 105, "run_ref": runner.nonce}
                self.assertEqual(runner.pending_cleanup_obligations, [obligation])
                self.assertEqual(runner.journal["pending_cleanup_obligations"], [obligation])
                self.assertEqual(runner._get_tracked_ids("categories"), set())
                self.assertIsNone(runner.category_id)
                if failure == "get":
                    with open(runner.get_journal_path(), encoding="utf-8") as journal:
                        self.assertEqual(json.load(journal)["pending_cleanup_obligations"], [obligation])

                transport.register_response("GET", path, 200, {"categories": []})
                self.assertEqual(runner.guarded_request("GET", path)[0], 200)
                attempts = len(transport.history)
                for method in ("POST", "PUT", "PATCH", "DELETE"):
                    target = path if method == "POST" else path + "/105"
                    with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
                        runner.guarded_request(method, target, body=payload)
                for target in ("/api/auth/login", "/api/auth/logout/extra"):
                    with self.assertRaises(QASecurityError):
                        runner.guarded_request("POST", target, body={})
                self.assertEqual(len(transport.history), attempts)
                self.assertEqual(sum(r["method"] == "POST" for r in transport.history), 1)
                self.assertFalse(any(r["method"] == "DELETE" for r in transport.history))
                transport.register_response("POST", "/api/auth/logout", 200, {"success": True})
                self.assertEqual(runner.guarded_request("POST", "/api/auth/logout")[0], 200)
                with self.assertRaises(QASecurityError):
                    runner.guarded_request("POST", path, body=payload)

    def test_verified_durable_creation_still_succeeds(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        path = "/api/admin/categories"
        payload = {"name_pt": f"qa_cat_{runner.nonce}"}
        transport.register_response("POST", path, 201, {"category": {"id": 106}})
        transport.register_response("GET", path, 200, {"categories": [{
            "id": 106, "name_pt": payload["name_pt"], "parent_category_id": None,
        }]})
        self.assertEqual(runner.guarded_request("POST", path, body=payload)[0], 201)
        self.assertEqual(runner._get_tracked_ids("categories"), {106})
        self.assertEqual(runner.pending_cleanup_obligations, [])
        self.assertEqual([r["method"] for r in transport.history], ["POST", "GET"])

    def test_uncertain_post_malformed_responses_and_timeout_stop_mutations(self):
        cases = [
            ("null", 201, None),
            ("list", 201, []),
            ("malformed_str", 200, "invalid-json"),
            ("missing_id", 201, {"category": {"name_pt": "qa_cat"}}),
            ("bool_id", 200, {"category": {"id": True}}),
            ("float_id", 201, {"category": {"id": 105.5}}),
            ("zero_id", 200, {"category": {"id": 0}}),
            ("negative_id", 201, {"category": {"id": -1}}),
            ("timeout", None, None),
        ]
        path = "/api/admin/categories"
        for label, status, body in cases:
            with self.subTest(case=label):
                transport = FakeHTTPTransport()
                runner = self.make_runner(transport=transport)
                payload = {"name_pt": f"qa_cat_{runner.nonce}"}
                if label == "timeout":
                    transport.register_error("POST", path, TimeoutError("simulated post timeout"))
                else:
                    transport.register_response("POST", path, status, body)

                with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
                    runner.guarded_request("POST", path, body=payload)

                post_calls = [r for r in transport.history if r["method"] == "POST" and r["path"] == path]
                self.assertEqual(len(post_calls), 1)

                expected_obl = {"entity_type": "categories", "run_ref": runner.nonce}
                self.assertEqual(runner.pending_cleanup_obligations, [expected_obl])
                self.assertEqual(runner.journal["pending_cleanup_obligations"], [expected_obl])
                self.assertNotIn("id", runner.pending_cleanup_obligations[0])
                self.assertIsNone(runner.pending_cleanup_obligations[0].get("id"))

                self.assertEqual(runner._get_tracked_ids("categories"), set())
                self.assertIsNone(runner.category_id)

                history_before = len(transport.history)
                for method in ("POST", "PUT", "PATCH", "DELETE"):
                    target = path if method == "POST" else f"{path}/105"
                    with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
                        runner.guarded_request(method, target, body=payload)
                self.assertEqual(len(transport.history), history_before)

                transport.register_response("GET", path, 200, {"categories": []})
                self.assertEqual(runner.guarded_request("GET", path)[0], 200)
                transport.register_response("POST", "/api/auth/logout", 200, {"success": True})
                self.assertEqual(runner.guarded_request("POST", "/api/auth/logout")[0], 200)

    def test_prior_verified_fixture_with_uncertain_post_blocks_all_domain_mutations_and_cleanup(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"

        # 1. Prior verified active fixture: Category 106
        cat_path = "/api/admin/categories"
        cat_payload = {"name_pt": f"qa_cat_{runner.nonce}"}
        transport.register_response("POST", cat_path, 201, {"category": {"id": 106}})
        transport.register_response("GET", cat_path, 200, {"categories": [{
            "id": 106, "name_pt": cat_payload["name_pt"], "parent_category_id": None,
        }]})
        status, _, _ = runner.guarded_request("POST", cat_path, body=cat_payload)
        self.assertEqual(status, 201)
        self.assertEqual(runner._get_tracked_ids("categories"), {106})
        self.assertEqual(runner.category_id, 106)

        # 2. Trigger uncertain fixture POST: vehicles creation transport failure
        veh_path = "/api/admin/vehicles"
        veh_payload = {
            "description": f"qa_vehicle_{runner.nonce}",
            "license_plate": f"QA{runner.nonce[:5].upper()}",
        }
        transport.register_error("POST", veh_path, TimeoutError("simulated vehicle post timeout"))
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", veh_path, body=veh_payload)

        self.assertTrue(runner._domain_mutation_stopped)
        self.assertEqual(runner.blocked_reason, "ERR_POST_CREATION_UNVERIFIED")

        # 3. Verify POST, PUT, PATCH, DELETE are blocked pre-transport (even for prior verified fixture)
        history_before = len(transport.history)
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", "/api/admin/tasks", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("PUT", "/api/admin/categories/106", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("PATCH", "/api/admin/categories/106", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("DELETE", "/api/admin/categories/106")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/auth/login", body={})
        self.assertEqual(len(transport.history), history_before)

        # 4. Verify actual reverse cleanup is blocked pre-transport; no retries, no false cleanup grants
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 106, "name_pt": cat_payload["name_pt"]}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        transport.register_response("GET", "/api/admin/vehicles", 200, {"vehicles": []})
        transport.register_response("GET", "/api/admin/users", 200, {"users": []})
        transport.register_response("POST", "/api/auth/logout", 200, {"success": True})

        with self.assertRaises(QASecurityError):
            runner.execute_reverse_cleanup()

        self.assertFalse(any(r["method"] == "DELETE" for r in transport.history))
        self.assertNotIn("category", runner.journal["cleaned_up"])
        self.assertTrue(any(r.get("type") == "category" and r.get("id") == 106 for r in runner.journal["residuals"]))

        # 5. GET investigation and exact own logout preserved
        transport.register_response("GET", "/api/admin/categories", 200, {"categories": [{"id": 106}]})
        get_status, get_body, _ = runner.guarded_request("GET", "/api/admin/categories")
        self.assertEqual(get_status, 200)

        transport.register_response("POST", "/api/auth/logout", 200, {"success": True})
        logout_status, logout_body, _ = runner.guarded_request("POST", "/api/auth/logout", body={})
        self.assertEqual(logout_status, 200)

    def test_prior_verified_fixture_with_post_replace_fsync_uncertainty_blocks_all_domain_mutations_and_cleanup(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"

        # 1. Prior verified active fixture: Category 206
        cat_path = "/api/admin/categories"
        cat_payload = {"name_pt": f"qa_cat_{runner.nonce}"}
        transport.register_response("POST", cat_path, 201, {"category": {"id": 206}})
        transport.register_response("GET", cat_path, 200, {"categories": [{
            "id": 206, "name_pt": cat_payload["name_pt"], "parent_category_id": None,
        }]})
        status, _, _ = runner.guarded_request("POST", cat_path, body=cat_payload)
        self.assertEqual(status, 201)
        self.assertEqual(runner._get_tracked_ids("categories"), {206})
        self.assertEqual(runner.category_id, 206)

        # 2. Trigger post-replace directory fsync uncertainty
        orig_fsync = os.fsync
        def failing_dir_fsync(fd):
            try:
                st = os.fstat(fd)
                if stat.S_ISDIR(st.st_mode):
                    raise OSError("Simulated post-replace directory fsync failure")
            except OSError:
                raise
            return orig_fsync(fd)

        from unittest.mock import patch
        with patch("os.fsync", side_effect=failing_dir_fsync):
            with self.assertRaisesRegex(QASecurityError, "^ERR_JOURNAL_DURABILITY_UNCERTAIN"):
                runner.sync_journal()

        self.assertTrue(runner._domain_mutation_stopped)
        self.assertEqual(runner.blocked_reason, "ERR_JOURNAL_DURABILITY_UNCERTAIN")

        # 3. Verify POST, PUT, PATCH, DELETE are blocked pre-transport (even for prior verified fixture)
        history_before = len(transport.history)
        with self.assertRaisesRegex(QASecurityError, "^ERR_JOURNAL_DURABILITY_UNCERTAIN"):
            runner.guarded_request("POST", "/api/admin/tasks", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_JOURNAL_DURABILITY_UNCERTAIN"):
            runner.guarded_request("PUT", "/api/admin/categories/206", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_JOURNAL_DURABILITY_UNCERTAIN"):
            runner.guarded_request("PATCH", "/api/admin/categories/206", body={"name_pt": "test"})
        with self.assertRaisesRegex(QASecurityError, "^ERR_JOURNAL_DURABILITY_UNCERTAIN"):
            runner.guarded_request("DELETE", "/api/admin/categories/206")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/auth/login", body={})
        self.assertEqual(len(transport.history), history_before)

        # 4. Verify actual reverse cleanup is blocked pre-transport; no retries, no false cleanup grants
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 206, "name_pt": cat_payload["name_pt"]}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        transport.register_response("GET", "/api/admin/vehicles", 200, {"vehicles": []})
        transport.register_response("GET", "/api/admin/users", 200, {"users": []})
        transport.register_response("POST", "/api/auth/logout", 200, {"success": True})

        with self.assertRaises(QASecurityError):
            runner.execute_reverse_cleanup()

        self.assertFalse(any(r["method"] == "DELETE" for r in transport.history))
        self.assertNotIn("category", runner.journal["cleaned_up"])
        self.assertTrue(any(r.get("type") == "category" and r.get("id") == 206 for r in runner.journal["residuals"]))

        # 5. GET investigation and exact own logout preserved
        transport.register_response("GET", "/api/admin/categories", 200, {"categories": [{"id": 206}]})
        get_status, get_body, _ = runner.guarded_request("GET", "/api/admin/categories")
        self.assertEqual(get_status, 200)

        transport.register_response("POST", "/api/auth/logout", 200, {"success": True})
        logout_status, logout_body, _ = runner.guarded_request("POST", "/api/auth/logout", body={})
        self.assertEqual(logout_status, 200)


class TestVerificationBeforeDeleteAndNoForeignDeletion(BaseIsolatedQATestCase):
    """Verifies GET checks owner nonce before DELETE and halts if foreign tasks exist in QA category."""

    def test_refuse_delete_foreign_task(self):
        transport = FakeHTTPTransport()
        # Register a task that belongs to a different nonce / user
        transport.register_response("GET", "/api/admin/tasks", status=200, body={
            "tasks": [{"id": 99, "name_pt": "Production Important Task", "category_id": 10}]
        })
        runner = self.make_runner(transport=transport, execute_production=True)
        # Attempting to delete task 99 which does NOT have our nonce must be refused
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(task_id=99, token="admin_token")

    def test_refuse_delete_foreign_vehicle(self):
        transport = FakeHTTPTransport()
        transport.register_response("GET", "/api/admin/vehicles", status=200, body={
            "vehicles": [{"id": 77, "description": "Production Main Truck", "license_plate": "ABC1234"}]
        })
        runner = self.make_runner(transport=transport, execute_production=True, nonce="myaffix1")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_vehicle(vehicle_id=77, token="admin_token")

    def test_refuse_delete_foreign_worker(self):
        transport = FakeHTTPTransport()
        transport.register_response("GET", "/api/admin/users", status=200, body={
            "users": [{"id": 5, "username": "real_admin", "role_id": 1}]
        })
        runner = self.make_runner(transport=transport, execute_production=True, nonce="myaffix2")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(worker_id=5, token="admin_token")

    def test_refuse_category_cascade_if_foreign_tasks_present(self):
        transport = FakeHTTPTransport()
        cat_id = 55
        # Category has our nonce, but tasks list contains a task from someone else inside this category!
        transport.register_response("GET", "/api/admin/categories", status=200, body={
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{transport.default_nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", status=200, body={
            "tasks": [{"id": 101, "name_pt": "Foreign Task In Cat", "category_id": cat_id}]
        })
        runner = self.make_runner(transport=transport, execute_production=True, nonce=transport.default_nonce)
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)


class TestFailClosedCategoryDeletion(BaseIsolatedQATestCase):
    """Finding 1: Category deletion must fail closed on non-200 or malformed task list,
    and must verify child categories and child tasks before cascade."""

    def test_refuse_category_delete_on_failed_tasks_discovery(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf101")
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": "qa_cat_catf101", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 500, {"error": "Server error"})
        with self.assertRaises(Exception):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_malformed_tasks_discovery(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf102")
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": "qa_cat_catf102", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"unexpected_key": []})
        with self.assertRaises(Exception):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_when_foreign_child_category_present(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf103")
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [
                {"id": 10, "name_pt": "qa_cat_catf103", "parent_category_id": None},
                {"id": 11, "name_pt": "Foreign Child Category", "parent_category_id": 10},
            ]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_malformed_categories_list(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf104")
        # Malformed categories response: duplicate IDs
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [
                {"id": 10, "name_pt": "qa_cat_catf104", "parent_category_id": None},
                {"id": 10, "name_pt": "qa_cat_duplicate_catf104", "parent_category_id": None},
            ]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        with self.assertRaises(Exception):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_malformed_tasks_list(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf105")
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": "qa_cat_catf105", "parent_category_id": None}]
        })
        # Malformed tasks: invalid task id
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": "not_an_int", "category_id": 10, "name_pt": "qa_task_admin_catf105"}]
        })
        with self.assertRaises(Exception):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_ambiguous_task_ownership(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="catf106")
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": "qa_cat_catf106", "parent_category_id": None}]
        })
        # Task in category has non-string/missing name_pt
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "category_id": 10, "name_pt": None}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_without_deleting_owned_tasks_if_foreign_task_coexists(self):
        transport = FakeHTTPTransport()
        nonce = "catf107"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        task_owned_id = 101
        task_foreign_id = 102

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [
                {"id": task_owned_id, "name_pt": f"qa_task_admin_{nonce}", "category_id": cat_id},
                {"id": task_foreign_id, "name_pt": "Foreign Task In Cat", "category_id": cat_id},
            ]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")
        # Ensure NO task was deleted before aborting
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_when_requery_proves_tasks_remain_or_fails(self):
        transport = FakeHTTPTransport()
        nonce = "catf108"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        task_id = 101

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })

        call_count = 0

        def handler(method: str, path: str, headers: dict, body: dict):
            nonlocal call_count
            if method == "GET" and path == "/api/admin/tasks":
                call_count += 1
                if call_count >= 3:
                    # Re-query after deleting task returns error or remaining tasks
                    return 500, {"error": "Server error on re-query"}, {}
                return 200, {"tasks": [{"id": task_id, "name_pt": f"qa_task_admin_{nonce}", "category_id": cat_id}]}, {}
            if method == "DELETE" and path == f"/api/admin/tasks/{task_id}":
                return 200, {"success": True}, {}
            if method == "DELETE" and path == f"/api/admin/categories/{cat_id}":
                return 200, {"success": True}, {}
            return None

        transport.register_handler(handler)

        with self.assertRaises(Exception):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        cat_del_calls = [r for r in transport.history if r["method"] == "DELETE" and f"/api/admin/categories/{cat_id}" in r["path"]]
        self.assertEqual(len(cat_del_calls), 0)

    def test_successful_category_delete_with_owned_tasks_cleanup_and_requery(self):
        transport = FakeHTTPTransport()
        nonce = "catf109"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        task_id = 101

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })

        tasks_store = [
            {"id": task_id, "name_pt": f"qa_task_admin_{nonce}", "category_id": cat_id}
        ]

        def handler(method: str, path: str, headers: dict, body: dict):
            if method == "GET" and path == "/api/admin/tasks":
                return 200, {"tasks": list(tasks_store)}, {}
            if method == "DELETE" and path == f"/api/admin/tasks/{task_id}":
                tasks_store.clear()
                return 200, {"success": True}, {}
            if method == "DELETE" and path == f"/api/admin/categories/{cat_id}":
                return 200, {"success": True}, {}
            return None

        transport.register_handler(handler)

        runner.delete_verified_category(category_id=cat_id, token="admin_token")

        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 2)
        self.assertEqual(del_calls[0]["path"], f"/api/admin/tasks/{task_id}")
        self.assertEqual(del_calls[1]["path"], f"/api/admin/categories/{cat_id}")

    def test_refuse_category_delete_when_task_persists_on_requery_200(self):
        transport = FakeHTTPTransport()
        nonce = "catf110"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        task_id = 101

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        # Tasks re-query returns 200, but task still persists!
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": task_id, "name_pt": f"qa_task_admin_{nonce}", "category_id": cat_id}]
        })
        transport.register_response("DELETE", f"/api/admin/tasks/{task_id}", 200, {"success": True})

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        cat_del_calls = [r for r in transport.history if r["method"] == "DELETE" and f"/api/admin/categories/{cat_id}" in r["path"]]
        self.assertEqual(len(cat_del_calls), 0)

    def test_refuse_category_delete_when_foreign_task_appears_during_final_refresh_from_initial_empty(self):
        transport = FakeHTTPTransport()
        nonce = "catf111"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })

        call_count = 0

        def handler(method: str, path: str, headers: dict, body: dict):
            nonlocal call_count
            if method == "GET" and path == "/api/admin/tasks":
                call_count += 1
                if call_count == 1:
                    # Initially empty
                    return 200, {"tasks": []}, {}
                # During final refresh, foreign task appeared!
                return 200, {"tasks": [{"id": 102, "name_pt": "Foreign Injected", "category_id": cat_id}]}, {}
            return None

        transport.register_handler(handler)

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_when_child_category_appears_during_final_refresh(self):
        transport = FakeHTTPTransport()
        nonce = "catf112"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10

        cat_call_count = 0

        def handler(method: str, path: str, headers: dict, body: dict):
            nonlocal cat_call_count
            if method == "GET" and path == "/api/admin/categories":
                cat_call_count += 1
                if cat_call_count == 1:
                    return 200, {"categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]}, {}
                # During final categories refresh, child category appeared!
                return 200, {
                    "categories": [
                        {"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None},
                        {"id": 11, "name_pt": "Foreign Child Cat", "parent_category_id": cat_id},
                    ]
                }, {}
            if method == "GET" and path == "/api/admin/tasks":
                return 200, {"tasks": []}, {}
            return None

        transport.register_handler(handler)

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_foreign_category_name_containing_nonce_substring(self):
        transport = FakeHTTPTransport()
        nonce = "catf113"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        # Foreign category name containing nonce as substring
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"foreign_prefix_{nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_foreign_task_name_containing_nonce_substring(self):
        transport = FakeHTTPTransport()
        nonce = "catf114"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10

        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        # Foreign task name containing nonce as substring
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "name_pt": f"foreign_task_prefix_{nonce}", "category_id": cat_id}]
        })

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_refuse_category_delete_on_null_or_absent_relation_ids(self):
        transport = FakeHTTPTransport()
        nonce = "catf115"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)

        # 1. Category missing parent_category_id key
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{nonce}"}]  # missing parent_category_id!
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

        # 2. Task with null category_id
        transport.history.clear()
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "name_pt": f"qa_task_admin_{nonce}", "category_id": None}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=10, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

    def test_delete_verified_task_strict_validation_and_fail_closed(self):
        transport = FakeHTTPTransport()
        nonce = "taskf101"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        runner.category_id = 10

        # Foreign task name containing nonce substring
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "name_pt": f"foreign_task_{nonce}", "category_id": 10}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(task_id=101, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)

        # Malformed tasks GET with missing category_id key
        transport.history.clear()
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "name_pt": f"qa_task_admin_{nonce}"}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(task_id=101, token="admin_token")
        del_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(del_calls), 0)


class TestStrictWriteAllowlist(BaseIsolatedQATestCase):
    """Finding 2: Replace denylist-only protection with strict write allowlist:
    auth login/logout; creates only fixture categories/tasks/vehicles/users with expected exact own labels/body;
    PATCH/DELETE only captured/discovered and ownership-verified fixture IDs; original admin user never target."""

    def test_refuse_arbitrary_category_create_body(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": "Arbitrary Non Fixture"})

    def test_refuse_mutation_targeting_admin_user(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        runner.admin_user_id = 1
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/users/1", body={"is_active": 0})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/1")

    def test_refuse_mutation_of_unverified_resource_id(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/categories/999", body={"name_pt": f"qa_cat_edit_{runner.nonce}"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/categories/999")

    def test_refuse_create_user_with_admin_role(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner.nonce}",
                "password": "Password123!",
                "display_name": "QA Worker",
                "role_id": 1,  # Admin role is strictly forbidden
            })

    def test_refuse_unknown_mutation_routes(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        for method, path in [
            ("POST", "/api/admin/unknown_collection"),
            ("PATCH", "/api/admin/unknown_collection/1"),
            ("DELETE", "/api/admin/unknown_collection/1"),
            ("POST", "/api/admin/orders"),
            ("POST", "/api/v1/anything"),
            ("PUT", "/api/admin/categories/1"),
        ]:
            with self.subTest(method=method, path=path):
                with self.assertRaises(QASecurityError):
                    runner.guarded_request(method, path, body={})

    def test_refuse_auth_and_account_mutations(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        for method, path in [
            ("POST", "/api/auth/register"),
            ("POST", "/api/auth/reset-password"),
            ("POST", "/api/auth/change-password"),
            ("PATCH", "/api/auth/profile"),
            ("PUT", "/api/auth/profile"),
            ("DELETE", "/api/auth/profile"),
        ]:
            with self.subTest(method=method, path=path):
                with self.assertRaises(QASecurityError):
                    runner.guarded_request(method, path, body={})

    def test_refuse_foreign_untracked_and_wrong_collection_ids(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        cat_id = 10
        task_id = 20
        veh_id = 30
        worker_id = 40
        runner.category_id = cat_id
        runner.journal_record_resource("category", cat_id, f"qa_cat_{runner.nonce}")
        runner.admin_task_id = task_id
        runner.journal_record_resource("task", task_id, f"qa_task_admin_{runner.nonce}")
        runner.vehicle_id = veh_id
        runner.journal_record_resource("vehicle", veh_id, f"qa_veh_{runner.nonce}")
        runner.worker_id = worker_id
        runner.journal_record_resource("worker_user", worker_id, f"qa_worker_{runner.nonce}")

        # Wrong collection: using category ID for task mutation
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/tasks/{cat_id}", body={"name_pt": f"qa_task_admin_edit_{runner.nonce}"})
        # Wrong collection: using task ID for category mutation
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", f"/api/admin/categories/{task_id}")
        # Wrong collection: using worker ID for vehicle mutation
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/vehicles/{worker_id}", body={"is_active": 0})
        # Foreign / untracked ID
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/vehicles/999", body={"is_active": 0})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/999")
        # Non-positive integer IDs
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/tasks/0")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/tasks/-1")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/categories/invalid_id", body={"name_pt": "test"})

    def test_refuse_ambiguous_and_encoded_paths(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        ambiguous_paths = [
            ("POST", "/api/admin/%63ategories"),
            ("POST", "/api/admin/tasks/../drivers"),
            ("POST", "/api/admin//tasks"),
            ("PATCH", "/api/admin/tasks/1?force=true"),
            ("POST", "/api/admin/tasks#fragment"),
            ("POST", "/api/admin/./tasks"),
        ]
        for method, path in ambiguous_paths:
            with self.subTest(method=method, path=path):
                with self.assertRaises(QASecurityError):
                    runner.guarded_request(method, path, body={})

    def test_positive_owned_write_allowlist_fake_transport(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        nonce = runner.nonce
        cat_id = 10
        task_id = 20
        veh_id = 30
        worker_id = 40

        # Register responses in FakeHTTPTransport
        transport.register_response("POST", "/api/auth/login", 200, {"token": "tok1", "user": {"id": 1}})
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": cat_id}})
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        transport.register_response("POST", "/api/admin/tasks", 201, {"task": {"id": task_id}})
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": task_id, "name_pt": f"qa_task_admin_{nonce}", "category_id": cat_id}]
        })
        transport.register_response("POST", "/api/admin/vehicles", 201, {"vehicle": {"id": veh_id}})
        transport.register_response("GET", "/api/admin/vehicles", 200, {
            "vehicles": [{"id": veh_id, "description": f"qa_vehicle_{nonce}", "license_plate": f"QA{nonce[:5].upper()}"}]
        })
        transport.register_response("POST", "/api/admin/users", 201, {"user": {"id": worker_id}})
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": worker_id, "username": f"qa_worker_{nonce}", "role_id": 2}]
        })
        transport.register_response("PATCH", f"/api/admin/categories/{cat_id}", 200, {"category": {"id": cat_id}})
        transport.register_response("PATCH", f"/api/admin/tasks/{task_id}", 200, {"task": {"id": task_id}})
        transport.register_response("PATCH", f"/api/admin/vehicles/{veh_id}", 200, {"vehicle": {"id": veh_id}})
        transport.register_response("PATCH", f"/api/admin/users/{worker_id}", 200, {"user": {"id": worker_id}})
        transport.register_response("DELETE", f"/api/admin/tasks/{task_id}", 200, {"success": True})
        transport.register_response("DELETE", f"/api/admin/categories/{cat_id}", 200, {"success": True})
        transport.register_response("DELETE", f"/api/admin/vehicles/{veh_id}", 200, {"success": True})
        transport.register_response("DELETE", f"/api/admin/users/{worker_id}", 200, {"success": True})
        transport.register_response("POST", "/api/auth/logout", 200, {"success": True})

        # 1. Login
        st, b, _ = runner.guarded_request("POST", "/api/auth/login", body={"username": "admin", "password": "pw"})
        self.assertEqual(st, 200)

        # 2. POST category with valid session-owned payload
        st, b, _ = runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{nonce}", "name_es": f"qa_cat_{nonce}"})
        self.assertEqual(st, 201)
        runner.category_id = cat_id
        runner.journal_record_resource("category", cat_id, f"qa_cat_{nonce}")

        # 3. POST task with valid session-owned payload and tracked category_id
        st, b, _ = runner.guarded_request("POST", "/api/admin/tasks", body={"category_id": cat_id, "name_pt": f"qa_task_admin_{nonce}"})
        self.assertEqual(st, 201)
        runner.admin_task_id = task_id
        runner.journal_record_resource("task", task_id, f"qa_task_admin_{nonce}")

        # 4. POST vehicle with valid session-owned payload
        st, b, _ = runner.guarded_request("POST", "/api/admin/vehicles", body={"description": f"qa_vehicle_{nonce}", "license_plate": f"QA{nonce[:5].upper()}"})
        self.assertEqual(st, 201)
        runner.vehicle_id = veh_id
        runner.journal_record_resource("vehicle", veh_id, f"qa_vehicle_{nonce}")

        # 5. POST user with role_id 2 and valid session-owned payload
        st, b, _ = runner.guarded_request("POST", "/api/admin/users", body={"username": f"qa_worker_{nonce}", "password": "pw!", "role_id": 2})
        self.assertEqual(st, 201)
        runner.worker_id = worker_id
        runner.journal_record_resource("worker_user", worker_id, f"qa_worker_{nonce}")

        # 6. PATCH on tracked IDs
        st, _, _ = runner.guarded_request("PATCH", f"/api/admin/categories/{cat_id}", body={"name_pt": f"qa_cat_edit_{nonce}"})
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("PATCH", f"/api/admin/tasks/{task_id}", body={"name_pt": f"qa_task_admin_edit_{nonce}"})
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("PATCH", f"/api/admin/vehicles/{veh_id}", body={"is_active": 0})
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"is_active": 0})
        self.assertEqual(st, 200)

        # 7. DELETE on tracked IDs
        st, _, _ = runner.guarded_request("DELETE", f"/api/admin/tasks/{task_id}")
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("DELETE", f"/api/admin/categories/{cat_id}")
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("DELETE", f"/api/admin/vehicles/{veh_id}")
        self.assertEqual(st, 200)
        st, _, _ = runner.guarded_request("DELETE", f"/api/admin/users/{worker_id}")
        self.assertEqual(st, 200)

        # 8. Logout
        st, _, _ = runner.guarded_request("POST", "/api/auth/logout", body={})
        self.assertEqual(st, 200)


class TestTrustBoundaryAndOwnershipAdversarial(BaseIsolatedQATestCase):
    """Adversarial security tests for Trust-Boundary and Ownership invariants:
    - Persisted journal and caller attribute claims never grant write authority without active session proof
    - New fixture creation rejects nonce substrings, prefix-only vehicle plates, and unowned task categories
    - /users ID routes reject existing, admin, or unproven IDs even when admin_user_id is None
    - PATCH mutations cannot change ownership markers, category relations, or role away from 2
    - delete_verified_* does not mint ownership before validation; failed cascades leave authority unchanged
    - All rejected mutation attempts result in zero transport write calls
    """

    def test_refuse_write_authorized_only_by_untrusted_journal(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        # Untrusted journal claim without active session POST proof
        runner.journal["resources"]["category"] = {"id": 100, "name": f"qa_cat_{runner.nonce}"}
        runner.journal["resources"]["tasks"].append({"id": 200, "name": f"qa_task_admin_{runner.nonce}"})
        runner.journal["resources"]["vehicle"] = {"id": 300, "plate": f"QA{runner.nonce[:5].upper()}"}
        runner.journal["resources"]["worker_user"] = {"id": 400, "username": f"qa_worker_{runner.nonce}"}

        # Attempting PATCH or DELETE on any of these IDs must fail closed
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/categories/100", body={"name_pt": f"qa_cat_edit_{runner.nonce}"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/categories/100")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/tasks/200", body={"name_pt": f"qa_task_admin_edit_{runner.nonce}"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/tasks/200")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/vehicles/300")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/400")

        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_write_authorized_only_by_setting_attribute(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        # Directly setting runner attributes without active session POST proof
        runner.category_id = 111
        runner.admin_task_id = 222
        runner.worker_task_id = 333
        runner.vehicle_id = 444
        runner.worker_id = 555

        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/categories/111", body={"name_pt": f"qa_cat_edit_{runner.nonce}"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/categories/111")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/tasks/222", body={"name_pt": f"qa_task_admin_edit_{runner.nonce}"})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/tasks/222")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/vehicles/444")
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/555")

        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_category_create_with_nonce_substring_instead_of_exact_label(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        for bad_name in [
            f"foreign_prefix_{runner.nonce}",
            f"qa_cat_{runner.nonce}_suffix",
            f"attacker_{runner.nonce}",
            f"{runner.nonce}",
        ]:
            with self.subTest(bad_name=bad_name):
                with self.assertRaises(QASecurityError):
                    runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": bad_name})
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_task_create_with_nonce_substring_instead_of_exact_label(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": 10}})
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{runner.nonce}"})
        setup_writes = len([r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")])

        for bad_name in [
            f"foreign_task_{runner.nonce}",
            f"qa_task_admin_{runner.nonce}_extra",
            f"attacker_task_{runner.nonce}",
            f"{runner.nonce}",
        ]:
            with self.subTest(bad_name=bad_name):
                with self.assertRaises(QASecurityError):
                    runner.guarded_request("POST", "/api/admin/tasks", body={"category_id": 10, "name_pt": bad_name})
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), setup_writes)

    def test_refuse_task_create_when_no_active_owned_category(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        # Empty registry: no active owned category exists
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/tasks", body={
                "category_id": 999,
                "name_pt": f"qa_task_admin_{runner.nonce}",
            })
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_task_create_with_foreign_category_id_when_owned_exists(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": 10}})
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{runner.nonce}"})
        setup_writes = len([r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")])

        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/tasks", body={
                "category_id": 999,  # foreign category ID
                "name_pt": f"qa_task_admin_{runner.nonce}",
            })
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), setup_writes)

    def test_refuse_vehicle_create_with_prefix_only_or_substring(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        expected_plate = f"QA{runner.nonce[:5].upper()}"
        expected_desc = f"qa_vehicle_{runner.nonce}"

        # Prefix-only plate
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/vehicles", body={
                "description": expected_desc,
                "license_plate": f"QA{runner.nonce[:3].upper()}",
            })
        # Substring / non-matching description
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/vehicles", body={
                "description": f"foreign_{runner.nonce}",
                "license_plate": expected_plate,
            })
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_worker_create_with_non_worker_role_or_bad_username(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        expected_user = f"qa_worker_{runner.nonce}"

        # Non-worker role
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": expected_user,
                "password": "Password123!",
                "role_id": 3,
            })
        # Role 2 but username is substring / extra affix
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"other_worker_{runner.nonce}",
                "password": "Password123!",
                "role_id": 2,
            })
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_user_mutation_when_admin_user_id_is_none(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        self.assertIsNone(runner.admin_user_id)

        # Neither admin user nor unproven user can be mutated when admin_user_id is None
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/users/1", body={"is_active": 0})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/1")
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_user_mutation_targeting_existing_or_unproven_id(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", "/api/admin/users/42", body={"is_active": 0})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", "/api/admin/users/42")
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_refuse_patch_changing_ownership_markers_or_foreign_relations(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        cat_id = 10
        task_id = 20
        veh_id = 30
        worker_id = 40
        # Genuinely active owned resources via legitimate fake POST+GET verified flow
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": cat_id}})
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{runner.nonce}"})

        transport.register_response("POST", "/api/admin/tasks", 201, {"task": {"id": task_id}})
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": task_id, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": cat_id}]
        })
        runner.guarded_request("POST", "/api/admin/tasks", body={"category_id": cat_id, "name_pt": f"qa_task_admin_{runner.nonce}"})

        transport.register_response("POST", "/api/admin/vehicles", 201, {"vehicle": {"id": veh_id}})
        transport.register_response("GET", "/api/admin/vehicles", 200, {
            "vehicles": [{"id": veh_id, "description": f"qa_vehicle_{runner.nonce}", "license_plate": f"QA{runner.nonce[:5].upper()}"}]
        })
        runner.guarded_request("POST", "/api/admin/vehicles", body={"description": f"qa_vehicle_{runner.nonce}", "license_plate": f"QA{runner.nonce[:5].upper()}"})

        transport.register_response("POST", "/api/admin/users", 201, {"user": {"id": worker_id}})
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": worker_id, "username": f"qa_worker_{runner.nonce}", "role_id": 2}]
        })
        runner.guarded_request("POST", "/api/admin/users", body={"username": f"qa_worker_{runner.nonce}", "password": "Password123!", "role_id": 2})

        setup_writes = len([r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")])

        # 1. Task: cannot change category_id to foreign
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/tasks/{task_id}", body={"category_id": 999})
        # 2. Task: cannot change name_pt to foreign
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/tasks/{task_id}", body={"name_pt": "Foreign Name"})
        # 3. Category: cannot change name_pt to foreign
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/categories/{cat_id}", body={"name_pt": "Foreign Cat"})
        # 4. Category: cannot set parent_category_id to foreign
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/categories/{cat_id}", body={"parent_category_id": 55})
        # 5. User: cannot change role away from 2
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"role_id": 1})
        # 6. User: cannot change username
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"username": "new_admin"})
        # 7. Vehicle: cannot change license_plate
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/vehicles/{veh_id}", body={"license_plate": "OTHER12"})
        # 8. Vehicle: cannot change description to foreign
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/vehicles/{veh_id}", body={"description": "Foreign Truck"})

        write_calls_after = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls_after), setup_writes)

    def test_delete_verified_task_refuses_when_no_active_category(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        # runner.category_id is None
        self.assertIsNone(runner.category_id)
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 101, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": 10}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(task_id=101, token="admin_token")
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_delete_verified_category_does_not_mint_ownership_on_failed_cascade(self):
        transport = FakeHTTPTransport()
        nonce = "cascfail"
        runner = self.make_runner(transport=transport, execute_production=True, nonce=nonce)
        cat_id = 10
        # Category exists with our nonce, but has foreign task
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 999, "name_pt": "Foreign Task", "category_id": cat_id}]
        })

        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(category_id=cat_id, token="admin_token")

        # Crucial: cat_id must NOT be in tracked IDs for mutations
        self.assertNotIn(cat_id, runner._get_tracked_ids("categories"))
        # Subsequent PATCH/DELETE must fail
        with self.assertRaises(QASecurityError):
            runner.guarded_request("DELETE", f"/api/admin/categories/{cat_id}")
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_adversarial_post_response_foreign_label_refuses_ownership(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        cat_id = 99
        # Server returns cat_id on POST, but GET listing reveals foreign label
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": cat_id}})
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": "foreign_category_name", "parent_category_id": None}]
        })
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{runner.nonce}"})
        self.assertNotIn(cat_id, runner._get_tracked_ids("categories"))

    def test_adversarial_post_user_returning_admin_or_wrong_role_refuses_ownership(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        runner.admin_user_id = 1
        # 1. Server returns existing admin user id 1
        transport.register_response("POST", "/api/admin/users", 201, {"user": {"id": 1}})
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 1, "username": f"qa_worker_{runner.nonce}", "role_id": 2}]
        })
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner.nonce}",
                "password": "Password123!",
                "role_id": 2,
            })
        self.assertNotIn(1, runner._get_tracked_ids("users"))
        self.assertNotEqual(runner.worker_id, 1)

        # 2. Server returns new id 77, but GET reveals role_id is 1 (admin)
        transport2 = FakeHTTPTransport()
        runner2 = self.make_runner(transport=transport2, execute_production=True)
        runner2.admin_user_id = 1
        transport2.register_response("POST", "/api/admin/users", 201, {"user": {"id": 77}})
        transport2.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 77, "username": f"qa_worker_{runner2.nonce}", "role_id": 1}]
        })
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner2.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner2.nonce}",
                "password": "Password123!",
                "role_id": 2,
            })
        self.assertNotIn(77, runner2._get_tracked_ids("users"))
        self.assertNotEqual(runner2.worker_id, 77)

    def test_adversarial_post_task_wrong_relation_refuses_ownership(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        owned_cat_id = 10
        runner._active_verified_ownership["categories"][owned_cat_id] = {"name_pt": f"qa_cat_{runner.nonce}"}
        runner.category_id = owned_cat_id

        task_id = 88
        transport.register_response("POST", "/api/admin/tasks", 201, {"task": {"id": task_id}})
        # GET reveals task is linked to foreign category 999 instead of owned 10
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": task_id, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": 999}]
        })
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", "/api/admin/tasks", body={
                "category_id": owned_cat_id,
                "name_pt": f"qa_task_admin_{runner.nonce}",
            })
        self.assertNotIn(task_id, runner._get_tracked_ids("tasks"))

    def test_adversarial_post_absence_proof_refuses_ownership(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        cat_id = 123
        transport.register_response("POST", "/api/admin/categories", 201, {"category": {"id": cat_id}})
        # GET returns empty list: resource is absent
        transport.register_response("GET", "/api/admin/categories", 200, {"categories": []})
        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request("POST", "/api/admin/categories", body={"name_pt": f"qa_cat_{runner.nonce}"})
        self.assertNotIn(cat_id, runner._get_tracked_ids("categories"))

    def test_adversarial_synthetic_helper_misuse_prohibited(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        self.assertFalse(hasattr(runner, "register_synthetic_verified_resource"))

    def test_adversarial_failed_cascade_no_new_grants_and_preserves_existing(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        unowned_cat_id = 50
        # 1. Unowned category: cascade fails during task deletion
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": unowned_cat_id, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 501, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": unowned_cat_id}]
        })
        transport.register_error("DELETE", "/api/admin/tasks/501", Exception("Task deletion failed"))

        with self.assertRaises(Exception):
            runner.delete_verified_category(unowned_cat_id, token="token")

        # Crucial invariant: unowned category must NOT have been granted ownership
        self.assertNotIn(unowned_cat_id, runner._get_tracked_ids("categories"))

        # 2. Existing legitimately owned category: cascade failure must NOT erase existing authority
        owned_cat_id = 60
        runner._active_verified_ownership["categories"][owned_cat_id] = {"name_pt": f"qa_cat_{runner.nonce}"}
        runner.category_id = owned_cat_id
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": owned_cat_id, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 601, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": owned_cat_id}]
        })
        transport.register_error("DELETE", "/api/admin/tasks/601", Exception("Task deletion failed"))

        with self.assertRaises(Exception):
            runner.delete_verified_category(owned_cat_id, token="token")

        # Crucial invariant: legitimate existing authority is preserved
        self.assertIn(owned_cat_id, runner._get_tracked_ids("categories"))

    def test_adversarial_revalidate_and_delete_reject_bool_and_float_ids(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)

        # 1. GET response with bool ID must not match int ID 1 in revalidation
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": True, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        self.assertFalse(runner.revalidate_resource("categories", 1))
        self.assertFalse(runner.revalidate_resource("categories", True))

        # 2. GET response with float ID must not match int ID 1
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 1.0, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        self.assertFalse(runner.revalidate_resource("categories", 1))
        self.assertFalse(runner.revalidate_resource("categories", 1.0))

        # 3. delete_verified_* reject bool and float IDs with QASecurityError and zero DELETE transport
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(True, token="token")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(1.0, token="token")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(True, token="token")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_task(1.0, token="token")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(True, token="token")
        with self.assertRaises(QASecurityError):
            runner.delete_verified_category(1.0, token="token")

        delete_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(delete_calls), 0)

    def test_adversarial_relations_strict_nullable_absence_and_bool_rejected(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)

        # 1. Missing parent_category_id key (absence is not null)
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{runner.nonce}"}]
        })
        self.assertFalse(runner.revalidate_resource("categories", 10))
        self.assertNotIn(10, runner._get_tracked_ids("categories"))

        # 2. Boolean parent_category_id (False / True)
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": False}]
        })
        self.assertFalse(runner.revalidate_resource("categories", 10))

        # Setup valid category 10 for task relation tests
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 10, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        self.assertTrue(runner.revalidate_resource("categories", 10))

        # 3. Task relation missing category_id
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 20, "name_pt": f"qa_task_admin_{runner.nonce}"}]
        })
        self.assertFalse(runner.revalidate_resource("tasks", 20))

        # 4. Task relation boolean category_id (e.g. True matching category 1 if tracked)
        runner._active_verified_ownership["categories"][1] = {"name_pt": f"qa_cat_{runner.nonce}"}
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 20, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": True}]
        })
        self.assertFalse(runner.revalidate_resource("tasks", 20))

        # 5. Task relation float category_id (e.g. 1.0)
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 20, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": 1.0}]
        })
        self.assertFalse(runner.revalidate_resource("tasks", 20))

    def test_adversarial_worker_role_missing_boolean_float_rejected(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)

        # 1. Missing role_id in revalidate_resource
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}"}]
        })
        self.assertFalse(runner.revalidate_resource("users", 40))

        # 2. Boolean role_id in revalidate_resource
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}", "role_id": True}]
        })
        self.assertFalse(runner.revalidate_resource("users", 40))

        # 3. Float role_id (2.0) in revalidate_resource
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}", "role_id": 2.0}]
        })
        self.assertFalse(runner.revalidate_resource("users", 40))

        # 4. delete_verified_worker cannot mint role 2 from missing role_id response
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}"}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(40, token="admin_token")
        self.assertNotIn(40, runner._get_tracked_ids("users"))

        # 5. delete_verified_worker rejects boolean / float role_id
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}", "role_id": True}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(40, token="admin_token")

        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 40, "username": f"qa_worker_{runner.nonce}", "role_id": 2.0}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(40, token="admin_token")

        delete_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(delete_calls), 0)

    def test_adversarial_user1_protected_regardless_unknown_admin_user_id(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        # runner.admin_user_id is None (unknown)
        self.assertIsNone(runner.admin_user_id)

        # GET returns user 1 matching worker username and role 2
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": 1, "username": f"qa_worker_{runner.nonce}", "role_id": 2}]
        })

        # revalidate_resource must refuse ID 1
        self.assertFalse(runner.revalidate_resource("users", 1))
        self.assertNotIn(1, runner._get_tracked_ids("users"))

        # delete_verified_worker(1) must refuse with QASecurityError and NEVER call DELETE
        with self.assertRaises(QASecurityError):
            runner.delete_verified_worker(1, token="admin_token")

        delete_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(delete_calls), 0)

    def test_adversarial_post_worker_role_float_or_invalid_rejected_before_transport(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner.nonce}",
                "password": "Password123!",
                "role_id": 2.0,
            })
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner.nonce}",
                "password": "Password123!",
                "role_id": True,
            })
        with self.assertRaises(QASecurityError):
            runner.guarded_request("POST", "/api/admin/users", body={
                "username": f"qa_worker_{runner.nonce}",
                "password": "Password123!",
            })
        write_calls = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls), 0)

    def test_adversarial_patch_active_worker_role_float_rejected_before_transport(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True)
        worker_id = 40
        transport.register_response("POST", "/api/admin/users", 201, {"user": {"id": worker_id}})
        transport.register_response("GET", "/api/admin/users", 200, {
            "users": [{"id": worker_id, "username": f"qa_worker_{runner.nonce}", "role_id": 2}]
        })
        st, _, _ = runner.guarded_request("POST", "/api/admin/users", body={
            "username": f"qa_worker_{runner.nonce}",
            "password": "Password123!",
            "role_id": 2,
        })
        self.assertEqual(st, 201)
        setup_writes = len([r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")])

        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"role_id": 2.0})
        with self.assertRaises(QASecurityError):
            runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"role_id": True})

        write_calls_after = [r for r in transport.history if r["method"] in ("POST", "PUT", "PATCH", "DELETE")]
        self.assertEqual(len(write_calls_after), setup_writes)

        transport.register_response("PATCH", f"/api/admin/users/{worker_id}", 200, {"user": {"id": worker_id, "role_id": 2}})
        st_patch, _, _ = runner.guarded_request("PATCH", f"/api/admin/users/{worker_id}", body={"role_id": 2})
        self.assertEqual(st_patch, 200)


class TestUUIDNamespaceAndFixtureRecognition(BaseIsolatedQATestCase):
    """Finding 3: Full UUID namespace, not 8hex substring.
    Exact expected fixture fields/labels and IDs; vehicle plate-only matching never sufficient."""

    def test_default_namespace_is_full_uuid(self):
        runner = self.make_runner()
        parsed = uuid.UUID(runner.nonce)
        self.assertEqual(len(runner.nonce), 36)
        self.assertEqual(parsed.version, 4)

    def test_refuse_vehicle_delete_on_plate_only_match(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="vehf301")
        # Plate matches run prefix, but description is foreign
        transport.register_response("GET", "/api/admin/vehicles", 200, {
            "vehicles": [{"id": 33, "description": "Foreign Truck", "license_plate": "QAVEHF3"}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_vehicle(vehicle_id=33, token="admin_token")

    def test_refuse_vehicle_delete_on_desc_only_match(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport, execute_production=True, nonce="vehf301")
        # Description matches run fixture, but plate is foreign
        transport.register_response("GET", "/api/admin/vehicles", 200, {
            "vehicles": [{"id": 34, "description": f"qa_vehicle_{runner.nonce}", "license_plate": "FOREIGN"}]
        })
        with self.assertRaises(QASecurityError):
            runner.delete_verified_vehicle(vehicle_id=34, token="admin_token")

    def test_own_edited_labels_recognized_as_owned(self):
        runner = self.make_runner(nonce="ownf302")
        self.assertTrue(runner.is_owned_label("category", f"qa_cat_{runner.nonce}"))
        self.assertTrue(runner.is_owned_label("category", f"qa_cat_edit_{runner.nonce}"))
        self.assertFalse(runner.is_owned_label("category", "qa_cat_foreign"))
        self.assertTrue(runner.is_owned_label("task", f"qa_task_admin_{runner.nonce}"))
        self.assertTrue(runner.is_owned_label("task", f"qa_task_admin_edit_{runner.nonce}"))
        self.assertTrue(runner.is_owned_label("task", f"qa_task_worker_{runner.nonce}"))
        self.assertTrue(runner.is_owned_label("task", f"qa_task_worker_edit_{runner.nonce}"))
        self.assertFalse(runner.is_owned_label("task", "Production Task"))
        self.assertTrue(runner.is_owned_label("vehicle", f"qa_vehicle_{runner.nonce}"))
        self.assertTrue(runner.is_owned_label("vehicle", f"qa_vehicle_{runner.nonce}_updated"))
        self.assertFalse(runner.is_owned_label("vehicle", "Production Vehicle"))


class TestWorkerProbeHarmlessPatch(BaseIsolatedQATestCase):
    """Finding 4: Worker forbidden-write probe uses harmless PATCH of owned QA category
    instead of untagged POST /api/admin/categories."""

    def test_worker_probe_does_not_use_untagged_create(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="probef401",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        transport.setup_scenario_mocks(runner)
        runner.category_id = 301
        runner.admin_task_id = 401
        runner.worker_task_id = 402
        runner.worker_token = f"worker_mock_token_{runner.nonce}"

        runner._active_verified_ownership["categories"][301] = {"name_pt": f"qa_cat_{runner.nonce}"}
        runner._active_verified_ownership["tasks"][401] = {"name_pt": f"qa_task_admin_{runner.nonce}", "category_id": 301}
        runner._active_verified_ownership["tasks"][402] = {"name_pt": f"qa_task_worker_{runner.nonce}", "category_id": 301}

        runner.exercise_worker_authorization_matrix()

        # Check transport history: NO POST to /api/admin/categories with "Forbidden Cat"
        for req in transport.history:
            if req["method"] == "POST" and req["path"] == "/api/admin/categories":
                body = req.get("body") or {}
                self.assertNotEqual(body.get("name_pt"), "Forbidden Cat")

        # Check transport history: Harmless PATCH to owned category probe was attempted
        cat_patch_reqs = [
            req for req in transport.history
            if req["method"] == "PATCH" and req["path"] == f"/api/admin/categories/{runner.category_id}"
            and req.get("headers", {}).get("Authorization") == f"Bearer {runner.worker_token}"
        ]
        self.assertTrue(len(cat_patch_reqs) > 0)


class TestFailClosedCleanupAndPostConfirmation(BaseIsolatedQATestCase):
    """Finding 5: Discovery/logout failures cannot be swallowed or interpreted as empty successful cleanup.
    Persist sanitized residual/uncertain states and return failure; post-cleanup confirm absence of own entities."""

    def test_discovery_failure_during_cleanup_records_residual_and_fails(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="cleanf501",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"
        transport.register_response("GET", "/api/admin/tasks", 500, {"error": "Server error"})

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        self.assertTrue(len(runner.journal["residuals"]) > 0)

    def test_post_cleanup_verification_detects_residual(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="cleanf502",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"
        runner.category_id = 999
        # Server reports category still exists after DELETE attempted
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": 999, "name_pt": f"qa_cat_{runner.nonce}"}]
        })
        transport.register_response("GET", "/api/admin/tasks", 200, {"tasks": []})
        transport.register_response("DELETE", "/api/admin/categories/999", 200, {"success": True})
        transport.dynamic_state["categories"] = [{"id": 999, "name_pt": f"qa_cat_{runner.nonce}"}]

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        self.assertTrue(any(r.get("type") == "category" for r in runner.journal["residuals"]))

    def test_category_discovery_failure_during_cleanup_records_residual_and_fails(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="cleanf503",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"
        transport.register_response("GET", "/api/admin/categories", 500, {"error": "Server error"})

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        self.assertTrue(len(runner.journal["residuals"]) > 0)
        self.assertTrue(any(r.get("type") == "category" for r in runner.journal["residuals"]))

    def test_post_cleanup_verification_detects_residual_task(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="cleanf504",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"
        runner.admin_task_id = 701
        transport.register_response("GET", "/api/admin/categories", 200, {"categories": []})
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [{"id": 701, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": 99}]
        })
        transport.register_response("DELETE", "/api/admin/tasks/701", 200, {"success": True})

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        self.assertTrue(any(r.get("type") == "task" for r in runner.journal["residuals"]))

    def _assert_discovery_schema_failure(self, target_coll: str, path: str, expected_type: str, bad_body: Any):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="disc01",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"

        for p, k in [
            ("/api/admin/categories", "categories"),
            ("/api/admin/tasks", "tasks"),
            ("/api/admin/vehicles", "vehicles"),
            ("/api/admin/users", "users"),
        ]:
            if p == path:
                transport.register_response("GET", p, 200, bad_body)
            else:
                transport.register_response("GET", p, 200, {k: []})

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        # 1. Residual recorded with fixed code ERR_DISCOVERY_SCHEMA
        residuals = [r for r in runner.journal["residuals"] if r.get("type") == expected_type]
        self.assertTrue(len(residuals) > 0, f"No residual recorded for {target_coll}")
        self.assertEqual(residuals[0].get("error"), "ERR_DISCOVERY_SCHEMA")

        # 2. Pending cleanup obligation recorded
        obligations = [o for o in runner.pending_cleanup_obligations if o.get("entity_type") == target_coll]
        self.assertTrue(len(obligations) > 0, f"No pending obligation for {target_coll}")
        self.assertEqual(obligations[0].get("run_ref"), runner.nonce)
        journal_obligations = [o for o in runner.journal.get("pending_cleanup_obligations", []) if o.get("entity_type") == target_coll]
        self.assertTrue(len(journal_obligations) > 0)

        # 3. No active verified ownership granted
        self.assertEqual(len(runner._active_verified_ownership.get(target_coll, {})), 0)

        # 4. No assigned IDs
        if target_coll == "categories":
            self.assertIsNone(runner.category_id)
        elif target_coll == "tasks":
            self.assertIsNone(runner.admin_task_id)
            self.assertIsNone(runner.worker_task_id)
        elif target_coll == "vehicles":
            self.assertIsNone(runner.vehicle_id)
        elif target_coll == "users":
            self.assertIsNone(runner.worker_id)

        # 5. No DELETE calls issued
        delete_calls = [r for r in transport.history if r["method"] == "DELETE"]
        self.assertEqual(len(delete_calls), 0)

    # Categories discovery schema failures
    def test_discovery_schema_failure_categories_non_dict(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", ["not_a_dict"])

    def test_discovery_schema_failure_categories_missing_key(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"other": []})

    def test_discovery_schema_failure_categories_non_list(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": "not_a_list"})

    # Tasks discovery schema failures
    def test_discovery_schema_failure_tasks_non_dict(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", ["not_a_dict"])

    def test_discovery_schema_failure_tasks_missing_key(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"other": []})

    def test_discovery_schema_failure_tasks_non_list(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": "not_a_list"})

    # Vehicles discovery schema failures
    def test_discovery_schema_failure_vehicles_non_dict(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", ["not_a_dict"])

    def test_discovery_schema_failure_vehicles_missing_key(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"other": []})

    def test_discovery_schema_failure_vehicles_non_list(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": "not_a_list"})

    # Users discovery schema failures
    def test_discovery_schema_failure_users_non_dict(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", ["not_a_dict"])

    def test_discovery_schema_failure_users_missing_key(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"other": []})

    def test_discovery_schema_failure_users_non_list(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": "not_a_list"})

    # Categories discovery schema failures: empty entry, missing required fields, invalid types
    def test_discovery_schema_failure_categories_empty_entry(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{}]})

    def test_discovery_schema_failure_categories_missing_required_fields(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1}]})
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"name_pt": "cat"}]})

    def test_discovery_schema_failure_categories_invalid_types(self):
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": "bad", "name_pt": "cat"}]})
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1, "name_pt": 123}]})
        self._assert_discovery_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1, "name_pt": "cat", "parent_category_id": False}]})

    # Tasks discovery schema failures: empty entry, missing required fields, invalid types
    def test_discovery_schema_failure_tasks_empty_entry(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{}]})

    def test_discovery_schema_failure_tasks_missing_required_fields(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task"}]})
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "category_id": 10}]})
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"name_pt": "task", "category_id": 10}]})

    def test_discovery_schema_failure_tasks_invalid_types(self):
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": "bad", "name_pt": "task", "category_id": 10}]})
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": 123, "category_id": 10}]})
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task", "category_id": "not_int"}]})
        self._assert_discovery_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task", "category_id": False}]})

    # Vehicles discovery schema failures: empty entry, missing required fields, invalid types
    def test_discovery_schema_failure_vehicles_empty_entry(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{}]})

    def test_discovery_schema_failure_vehicles_missing_required_fields(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": "v"}]})
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "license_plate": "QA12345"}]})
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"description": "v", "license_plate": "QA12345"}]})

    def test_discovery_schema_failure_vehicles_invalid_types(self):
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": "bad", "description": "v", "license_plate": "QA12345"}]})
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": 123, "license_plate": "QA12345"}]})
        self._assert_discovery_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": "v", "license_plate": 123}]})

    # Users discovery schema failures: empty entry, missing required fields, invalid types
    def test_discovery_schema_failure_users_empty_entry(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{}]})

    def test_discovery_schema_failure_users_missing_required_fields(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u"}]})
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "role_id": 2}]})
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"username": "u", "role_id": 2}]})

    def test_discovery_schema_failure_users_invalid_types(self):
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": "bad", "username": "u", "role_id": 2}]})
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": 123, "role_id": 2}]})
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u", "role_id": "two"}]})
        self._assert_discovery_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u", "role_id": False}]})

    def _assert_confirmation_schema_failure(self, target_coll: str, check_path: str, expected_type: str, bad_body: Any):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="conf01",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        runner.admin_token = f"admin_mock_token_{runner.nonce}"

        call_counts = {check_path: 0}

        def handler(method, path, headers, body):
            if method == "GET" and path == check_path:
                call_counts[check_path] += 1
                if call_counts[check_path] == 1:
                    return 200, {target_coll: []}, {}
                else:
                    return 200, bad_body, {}
            return None

        transport.register_handler(handler)
        for p, k in [
            ("/api/admin/categories", "categories"),
            ("/api/admin/tasks", "tasks"),
            ("/api/admin/vehicles", "vehicles"),
            ("/api/admin/users", "users"),
        ]:
            if p != check_path:
                transport.register_response("GET", p, 200, {k: []})

        with self.assertRaises(Exception):
            runner.execute_reverse_cleanup()

        # 1. Residual recorded with fixed code ERR_POST_CLEANUP_SCHEMA
        residuals = [r for r in runner.journal["residuals"] if r.get("type") == expected_type]
        self.assertTrue(len(residuals) > 0, f"No residual recorded for confirmation of {target_coll}")
        self.assertEqual(residuals[0].get("error"), "ERR_POST_CLEANUP_SCHEMA")

        # 2. Pending cleanup obligation recorded
        obligations = [o for o in runner.pending_cleanup_obligations if o.get("entity_type") == target_coll]
        self.assertTrue(len(obligations) > 0, f"No pending obligation for confirmation of {target_coll}")
        self.assertEqual(obligations[0].get("run_ref"), runner.nonce)

        # 3. Never certifies clean
        self.assertTrue(len(runner.journal.get("residuals", [])) > 0)
        self.assertNotEqual(runner.journal.get("status"), "completed")

    # Categories confirmation schema failures
    def test_confirmation_schema_failure_categories_non_dict(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", ["not_a_dict"])

    def test_confirmation_schema_failure_categories_missing_key(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"other": []})

    def test_confirmation_schema_failure_categories_non_list(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": "not_a_list"})

    # Tasks confirmation schema failures
    def test_confirmation_schema_failure_tasks_non_dict(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", ["not_a_dict"])

    def test_confirmation_schema_failure_tasks_missing_key(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"other": []})

    def test_confirmation_schema_failure_tasks_non_list(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": "not_a_list"})

    # Vehicles confirmation schema failures
    def test_confirmation_schema_failure_vehicles_non_dict(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", ["not_a_dict"])

    def test_confirmation_schema_failure_vehicles_missing_key(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"other": []})

    def test_confirmation_schema_failure_vehicles_non_list(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": "not_a_list"})

    # Users confirmation schema failures
    def test_confirmation_schema_failure_users_non_dict(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", ["not_a_dict"])

    def test_confirmation_schema_failure_users_missing_key(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"other": []})

    def test_confirmation_schema_failure_users_non_list(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": "not_a_list"})

    # Categories confirmation schema failures: empty entry, missing required fields, invalid types
    def test_confirmation_schema_failure_categories_empty_entry(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{}]})

    def test_confirmation_schema_failure_categories_missing_required_fields(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1}]})
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"name_pt": "cat"}]})

    def test_confirmation_schema_failure_categories_invalid_types(self):
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": "bad", "name_pt": "cat"}]})
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1, "name_pt": 123}]})
        self._assert_confirmation_schema_failure("categories", "/api/admin/categories", "category", {"categories": [{"id": 1, "name_pt": "cat", "parent_category_id": False}]})

    # Tasks confirmation schema failures: empty entry, missing required fields, invalid types
    def test_confirmation_schema_failure_tasks_empty_entry(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{}]})

    def test_confirmation_schema_failure_tasks_missing_required_fields(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task"}]})
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "category_id": 10}]})
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"name_pt": "task", "category_id": 10}]})

    def test_confirmation_schema_failure_tasks_invalid_types(self):
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": "bad", "name_pt": "task", "category_id": 10}]})
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": 123, "category_id": 10}]})
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task", "category_id": "not_int"}]})
        self._assert_confirmation_schema_failure("tasks", "/api/admin/tasks", "task", {"tasks": [{"id": 1, "name_pt": "task", "category_id": False}]})

    # Vehicles confirmation schema failures: empty entry, missing required fields, invalid types
    def test_confirmation_schema_failure_vehicles_empty_entry(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{}]})

    def test_confirmation_schema_failure_vehicles_missing_required_fields(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": "v"}]})
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "license_plate": "QA12345"}]})
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"description": "v", "license_plate": "QA12345"}]})

    def test_confirmation_schema_failure_vehicles_invalid_types(self):
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": "bad", "description": "v", "license_plate": "QA12345"}]})
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": 123, "license_plate": "QA12345"}]})
        self._assert_confirmation_schema_failure("vehicles", "/api/admin/vehicles", "vehicle", {"vehicles": [{"id": 1, "description": "v", "license_plate": 123}]})

    # Users confirmation schema failures: empty entry, missing required fields, invalid types
    def test_confirmation_schema_failure_users_empty_entry(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{}]})

    def test_confirmation_schema_failure_users_missing_required_fields(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u"}]})
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "role_id": 2}]})
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"username": "u", "role_id": 2}]})

    def test_confirmation_schema_failure_users_invalid_types(self):
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": "bad", "username": "u", "role_id": 2}]})
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": 123, "role_id": 2}]})
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u", "role_id": "two"}]})
        self._assert_confirmation_schema_failure("users", "/api/admin/users", "worker_user", {"users": [{"id": 1, "username": "u", "role_id": False}]})


class TestFixedErrorCodesAndSensitiveOutputSuppression(BaseIsolatedQATestCase):
    """Finding 6: Never log raw exception text, redirect destination, arbitrary backend names/body or credentials/tokens.
    Emit fixed stage/error codes and HTTPstatus only."""

    def test_no_redirect_url_in_redirect_exception(self):
        transport = FakeHTTPTransport()
        secret_url = "https://trindademasas.duckdns.org/secret-destination?token=leakme123"
        transport.register_response("GET", "/api/test-redirect", status=302, headers={"Location": secret_url})
        runner = self.make_runner(transport=transport)
        with self.assertRaises(QASecurityError) as ctx:
            runner.guarded_request("GET", "/api/test-redirect")
        err_msg = str(ctx.exception)
        self.assertNotIn("secret-destination", err_msg)
        self.assertNotIn("leakme123", err_msg)
        self.assertIn("ERR_REDIRECT_REFUSED", err_msg)

    def test_approved_fixed_codes_preserved_without_payload_suffix(self):
        runner = self.make_runner()
        samples = [
            ("ERR_REDIRECT_REFUSED: HTTP Redirect (302) detected and strictly refused.", "ERR_REDIRECT_REFUSED"),
            ("ERR_JOURNAL_PERMS: Journal directory '/home/wilkin/...' foreign owner UID 1000 != 1001.", "ERR_JOURNAL_PERMS"),
            ("ERR_DELETE_FAILED: DELETE task failed with unexpected status 500", "ERR_DELETE_FAILED"),
            ("ERR_POST_CREATION_UNVERIFIED: registration proof failed for category", "ERR_POST_CREATION_UNVERIFIED"),
            ("ERR_CATEGORIES_MALFORMED: missing 'categories' object", "ERR_CATEGORIES_MALFORMED"),
            ("ERR_TASKS_STATUS: tasks listing failed with non-200 status", "ERR_TASKS_STATUS"),
            ("ERR_CLEANUP_RESIDUALS_EXIST: 2 residuals detected", "ERR_CLEANUP_RESIDUALS_EXIST"),
        ]
        for raw_msg, expected_code in samples:
            sanitized = runner.sanitize_error_message(raw_msg)
            self.assertEqual(sanitized, expected_code, f"Failed for {raw_msg}")
            self.assertNotIn(":", sanitized)

    def test_bounded_status_codes_preserve_only_status_code_without_payload(self):
        runner = self.make_runner()
        samples = [
            ("ERR_DISCOVERY_STATUS_500: Server encountered internal database crash", "ERR_DISCOVERY_STATUS_500"),
            ("ERR_POST_CLEANUP_CHECK_STATUS_404: Endpoint not found with trace", "ERR_POST_CLEANUP_CHECK_STATUS_404"),
            ("ERR_LOGOUT_STATUS_502: Bad gateway proxy response from backend", "ERR_LOGOUT_STATUS_502"),
            ("ERR_DISCOVERY_STATUS_200", "ERR_DISCOVERY_STATUS_200"),
        ]
        for raw_msg, expected_code in samples:
            sanitized = runner.sanitize_error_message(raw_msg)
            self.assertEqual(sanitized, expected_code)
            self.assertNotIn(":", sanitized)

    def test_unknown_err_looking_strings_and_arbitrary_exceptions_suppressed(self):
        runner = self.make_runner()
        unapproved = [
            "ERR_PRIVATE_DATA: super_secret_credential_token_999",
            "ERR_UNKNOWN_CODE_LEAK: sensitive_internal_host_192.168.1.1",
            "ERR_CUSTOM_INJECTION: SELECT * FROM users WHERE secret='val'",
        ]
        for raw_msg in unapproved:
            sanitized = runner.sanitize_error_message(raw_msg)
            self.assertNotIn("super_secret_credential_token_999", sanitized)
            self.assertNotIn("192.168.1.1", sanitized)
            self.assertNotIn("SELECT", sanitized)
            self.assertNotIn("ERR_PRIVATE_DATA", sanitized)
            self.assertNotIn("ERR_UNKNOWN_CODE_LEAK", sanitized)
            self.assertNotIn("ERR_CUSTOM_INJECTION", sanitized)
            self.assertEqual(sanitized, "ERR_INTERNAL_ERROR")

        self.assertEqual(runner.sanitize_error_message(str(KeyError("secret_key"))), "ERR_INTERNAL_ERROR")
        self.assertEqual(runner.sanitize_error_message(str(ValueError("invalid secret"))), "ERR_INTERNAL_ERROR")

    def test_http_transport_urlerror_suppresses_reason_and_maps_to_fixed_code(self):
        import urllib.error
        from unittest.mock import patch
        from verify_production_admin_qa import HTTPTransport

        transport = HTTPTransport(origin=ALLOWED_ORIGIN)
        secret_reason = "DNS lookup failed for internal_node_db_password_xyz.local:8080"

        with patch.object(transport.opener, "open", side_effect=urllib.error.URLError(reason=secret_reason)):
            with self.assertRaises(Exception) as ctx:
                transport.request("GET", "/api/admin/categories")

            err_msg = str(ctx.exception)
            self.assertNotIn("internal_node_db_password_xyz", err_msg)
            self.assertNotIn("8080", err_msg)
            self.assertNotIn(secret_reason, err_msg)
            self.assertIn("ERR_NETWORK_TRANSPORT", err_msg)

    def test_cli_stderr_suppresses_private_credentials_and_prints_fixed_code(self):
        import unittest.mock
        fake_home = tempfile.TemporaryDirectory()
        try:
            fake_config = os.path.join(fake_home.name, "fake-auth.json")
            fd = os.open(fake_config, os.O_WRONLY | os.O_CREAT, 0o600)
            with open(fd, "w") as f:
                json.dump({"admin": {"username": "admin", "password": "PRIVATE_SECRET_PASSWORD_CLI_LEAK"}}, f)

            with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as mock_stderr:
                with unittest.mock.patch(
                    "verify_production_admin_qa.ProductionQARunner.run",
                    side_effect=Exception("Unexpected DB leak: PRIVATE_SECRET_PASSWORD_CLI_LEAK"),
                ):
                    exit_code = main(["--execute-production", "--config", fake_config, "--journal-dir", fake_home.name])
                    self.assertEqual(exit_code, 1)

                stderr_val = mock_stderr.getvalue()
                self.assertNotIn("PRIVATE_SECRET_PASSWORD_CLI_LEAK", stderr_val)
                self.assertNotIn("Unexpected DB leak", stderr_val)
                self.assertIn("ERR_INTERNAL_ERROR", stderr_val)
        finally:
            fake_home.cleanup()

    def test_socket_error_sanitization_without_nameerror(self):
        from verify_production_admin_qa import sanitize_error_code

        sock_err = socket.error(110, "Connection timed out")
        code = sanitize_error_code(sock_err)
        self.assertEqual(code, "ERR_NETWORK_TRANSPORT")

        class WeirdError(Exception):
            def __str__(self):
                raise RuntimeError("failing str")

        self.assertEqual(sanitize_error_code(WeirdError()), "ERR_INTERNAL_ERROR")
        self.assertEqual(sanitize_error_code(None), "ERR_INTERNAL_ERROR")
        self.assertEqual(sanitize_error_code(12345), "ERR_INTERNAL_ERROR")

    def test_http_transport_constructor_invalid_origin_does_not_leak_raw_value(self):
        from verify_production_admin_qa import HTTPTransport, sanitize_error_code

        secret_origin = "https://secret-leak-attacker.internal:9999"
        with self.assertRaises(QASecurityError) as ctx:
            HTTPTransport(origin=secret_origin)

        err_msg = str(ctx.exception)
        self.assertNotIn(secret_origin, err_msg)
        self.assertNotIn("secret-leak-attacker", err_msg)
        self.assertIn("ERR_SECURITY_VIOLATION", err_msg)
        sanitized = sanitize_error_code(ctx.exception)
        self.assertEqual(sanitized, "ERR_SECURITY_VIOLATION")

    def test_cli_main_constructor_invalid_private_origin_suppresses_raw_origin(self):
        secret_origin = "https://sensitive-origin-secret-token.local:8443"
        fake_home = tempfile.TemporaryDirectory()
        try:
            fake_config = os.path.join(fake_home.name, "fake-auth.json")
            fd = os.open(fake_config, os.O_WRONLY | os.O_CREAT, 0o600)
            with open(fd, "w") as f:
                json.dump({"admin": {"username": "admin", "password": "mock_password"}}, f)

            with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as mock_stderr:
                exit_code = main(["--origin", secret_origin, "--config", fake_config, "--journal-dir", fake_home.name])
                self.assertEqual(exit_code, 1)

                stderr_val = mock_stderr.getvalue()
                self.assertNotIn(secret_origin, stderr_val)
                self.assertNotIn("sensitive-origin-secret-token", stderr_val)
                self.assertNotIn("Traceback", stderr_val)
                self.assertIn("ERR_SECURITY_VIOLATION", stderr_val)
        finally:
            fake_home.cleanup()


class TestFullScenarioAndReverseCleanup(BaseIsolatedQATestCase):
    """Verifies full execution lifecycle with FakeHTTPTransport:
    - Admin login
    - Worker creation (role 2) with random password
    - QA category & admin task creation
    - Worker login & worker task creation
    - Safe admin edit & vehicle fixture lifecycle
    - Worker authorization matrix (own edit succeeds, other edits/toggles/deletes/reads get 403)
    - Worker deactivation & 401 profile revocation
    - Reverse cleanup (tasks -> category -> vehicle -> worker -> logout)
    - Mid-run error triggers reverse cleanup
    """

    def test_full_scenario_success(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="run12345",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        transport.setup_scenario_mocks(runner)

        result = runner.run()
        self.assertEqual(result["status"], "success")
        self.assertTrue(result["worker_revocation_verified_401"])
        self.assertTrue(result["worker_matrix_verified_403"])
        self.assertEqual(result["residuals"], [])

        # Verify reverse cleanup order in history:
        # tasks deleted -> category deleted -> vehicle deleted -> worker deleted -> logout
        delete_paths = [req["path"] for req in transport.history if req["method"] == "DELETE"]
        self.assertTrue(any("/api/admin/tasks/" in p for p in delete_paths))
        self.assertTrue(any("/api/admin/categories/" in p for p in delete_paths))
        self.assertTrue(any("/api/admin/users/" in p for p in delete_paths))

        task_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/tasks/" in p)
        cat_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/categories/" in p)
        user_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/users/" in p)
        self.assertLess(task_del_idx, cat_del_idx)
        self.assertLess(cat_del_idx, user_del_idx)

    def test_mid_run_error_triggers_reverse_cleanup(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="err12345",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        transport.setup_scenario_mocks(runner)
        # Inject error during vehicle creation (uncertain POST trips STOP latch)
        transport.register_error("POST", "/api/admin/vehicles", Exception("Simulated mid-run crash"))

        with self.assertRaises(Exception):
            runner.run()

        # STOP latch tripped: blocks subsequent mutations including cleanup DELETEs
        self.assertTrue(runner._domain_mutation_stopped)
        self.assertEqual(runner.blocked_reason, "ERR_POST_CREATION_UNVERIFIED")

        # Reverse cleanup must NOT send DELETE requests over transport
        self.assertFalse(any(req["method"] == "DELETE" for req in transport.history))

        # Check that resources created prior to crash were NOT marked as cleaned up
        cleaned_up = runner.journal["cleaned_up"]
        self.assertNotIn("tasks", cleaned_up)
        self.assertNotIn("category", cleaned_up)
        self.assertNotIn("worker_user", cleaned_up)

        # Residuals must record the uncleaned resources
        residual_types = [r.get("type") for r in runner.journal["residuals"]]
        self.assertIn("task", residual_types)
        self.assertIn("category", residual_types)
        self.assertIn("worker_user", residual_types)

        # Own logout was still executed
        self.assertTrue(any(req["method"] == "POST" and req["path"] == "/api/auth/logout" for req in transport.history))

    def test_mid_run_ordinary_failure_executes_reverse_cleanup(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(
            transport=transport,
            execute_production=True,
            nonce="orderr12",
            admin_credentials={"username": "admin", "password": "mock_password"},
        )
        transport.setup_scenario_mocks(runner)
        # Inject non-uncertain ordinary failure during admin safe edits (PATCH returns 500)
        transport.register_response("PATCH", "/api/admin/categories/301", 500, {"error": "Server error"})

        with self.assertRaises(Exception):
            runner.run()

        # Stop latch was NOT tripped
        self.assertFalse(runner._domain_mutation_stopped)

        # Check that resources created prior to crash (category, tasks, worker) were cleaned up
        cleaned_up = runner.journal["cleaned_up"]
        self.assertIn("tasks", cleaned_up)
        self.assertIn("category", cleaned_up)
        self.assertIn("worker_user", cleaned_up)

        # Verify reverse cleanup order in history: tasks -> category -> worker -> logout
        delete_paths = [req["path"] for req in transport.history if req["method"] == "DELETE"]
        self.assertTrue(any("/api/admin/tasks/" in p for p in delete_paths))
        self.assertTrue(any("/api/admin/categories/" in p for p in delete_paths))
        self.assertTrue(any("/api/admin/users/" in p for p in delete_paths))

        task_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/tasks/" in p)
        cat_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/categories/" in p)
        user_del_idx = next(i for i, p in enumerate(delete_paths) if "/api/admin/users/" in p)
        self.assertLess(task_del_idx, cat_del_idx)
        self.assertLess(cat_del_idx, user_del_idx)

        # Logout was executed
        self.assertTrue(any(req["method"] == "POST" and req["path"] == "/api/auth/logout" for req in transport.history))


class TestAtomicJournalAndStateRegistrationRollback(BaseIsolatedQATestCase):
    """Tests atomic journal sync, rollback on persist failure, and cleanup obligation tracking."""

    def test_fault_injected_atomic_journal_write_preserves_old_file(self):
        runner = self.make_runner()
        runner.journal["nonce"] = runner.nonce
        runner.sync_journal()
        journal_path = runner.get_journal_path()
        with open(journal_path, "r", encoding="utf-8") as f:
            initial_content = f.read()

        # Inject failure during write/fsync to simulate crash before atomic commit
        orig_fsync = os.fsync
        def failing_fsync(fd):
            raise OSError("Simulated atomic fsync crash before commit")

        import unittest.mock
        with unittest.mock.patch("os.fsync", side_effect=failing_fsync):
            with self.assertRaises(OSError):
                runner.journal["nonce"] = "corrupted_overwrite_attempt"
                runner.sync_journal()

        # Old file must be completely preserved and unchanged
        with open(journal_path, "r", encoding="utf-8") as f:
            current_content = f.read()
        self.assertEqual(current_content, initial_content)

        # Staged temp files must be cleaned up
        temp_files = [f for f in os.listdir(runner.journal_dir) if ".tmp." in f]
        self.assertEqual(temp_files, [])

    def test_verified_get_persist_failure_no_authority_grant(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        runner.admin_token = "admin-token-123"

        # Setup valid GET response for category 99
        transport.register_response(
            "GET",
            "/api/admin/categories",
            200,
            {"categories": [{"id": 99, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]},
        )

        # Fault inject sync_journal to simulate disk failure during persist
        import unittest.mock
        with unittest.mock.patch.object(runner, "sync_journal", side_effect=OSError("Disk full")):
            ok = runner.revalidate_resource("categories", 99)
            self.assertFalse(ok)

        # Pre-existing state must be preserved: no authority granted
        self.assertNotIn(99, runner._active_verified_ownership.get("categories", {}))
        self.assertIsNone(runner.category_id)
        self.assertIsNone(runner.journal.get("resources", {}).get("category"))

    def test_post_201_revalidate_or_persist_failure_tracks_cleanup_obligation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        runner.admin_token = "admin-token-123"
        runner.admin_user_id = 1

        # POST succeeds remotely (201), but GET revalidation returns 500
        transport.register_response(
            "POST",
            "/api/admin/categories",
            201,
            {"category": {"id": 105, "name_pt": f"qa_cat_{runner.nonce}"}},
        )
        transport.register_response("GET", "/api/admin/categories", 500, {"error": "Server error during revalidation"})

        with self.assertRaisesRegex(QASecurityError, "^ERR_POST_CREATION_UNVERIFIED$"):
            runner.guarded_request(
                "POST",
                "/api/admin/categories",
                body={"name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None},
            )

        # No active write permission granted
        self.assertNotIn(105, runner._active_verified_ownership.get("categories", {}))
        self.assertIsNone(runner.category_id)

        # Cleanup obligation must be tracked separately
        self.assertTrue(hasattr(runner, "pending_cleanup_obligations"))
        obligations = [o for o in runner.pending_cleanup_obligations if o.get("id") == 105]
        self.assertEqual(len(obligations), 1)
        self.assertEqual(obligations[0]["entity_type"], "categories")
        self.assertEqual(obligations[0]["run_ref"], runner.nonce)

    def test_partial_cascade_one_delete_succeeded_preserves_truth_and_fails(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        token = "admin-token-123"
        cat_id = 200
        task1_id = 201
        task2_id = 202

        # Categories list has cat_id
        transport.register_response("GET", "/api/admin/categories", 200, {
            "categories": [{"id": cat_id, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]
        })
        # Tasks list has task1 and task2 in cat_id
        transport.register_response("GET", "/api/admin/tasks", 200, {
            "tasks": [
                {"id": task1_id, "name_pt": f"qa_task_admin_{runner.nonce}", "category_id": cat_id},
                {"id": task2_id, "name_pt": f"qa_task_worker_{runner.nonce}", "category_id": cat_id},
            ]
        })
        # Task 1 DELETE succeeds
        transport.register_response("DELETE", f"/api/admin/tasks/{task1_id}", 200, {"success": True})
        # Task 2 DELETE fails
        transport.register_error("DELETE", f"/api/admin/tasks/{task2_id}", Exception("DELETE task 202 failed"))

        with self.assertRaises(Exception):
            runner.delete_verified_category(cat_id, token)

        # Truthful tracking: task1 was deleted, so it must NOT be resurrected in ownership or journal!
        self.assertNotIn(task1_id, runner._active_verified_ownership.get("tasks", {}))
        # Category delete failed, so category was not deleted, and if it wasn't owned before,
        # it must not have acquired spurious final category deletion or false-clean status
        self.assertNotIn("category", runner.journal.get("cleaned_up", []))


class TestJournalFileSafetyAndDurability(BaseIsolatedQATestCase):
    """Verifies atomic journal safety, symlink/foreign-owner rejection, collision preservation, and postcommit durability signal."""

    def test_symlink_journal_dir_rejected_and_target_unchanged(self):
        real_dir = os.path.join(self.journal_dir, "real_target")
        os.makedirs(real_dir, mode=0o755)
        orig_mode = os.stat(real_dir).st_mode & 0o777
        symlink_dir = os.path.join(self.journal_dir, "symlink_dir")
        os.symlink(real_dir, symlink_dir)

        runner = self.make_runner(journal_dir=symlink_dir)
        with self.assertRaises(QASecurityError):
            runner.sync_journal()

        # Symlink target mode must remain unchanged (no chmod followed)
        self.assertEqual(os.stat(real_dir).st_mode & 0o777, orig_mode)

    def test_foreign_owner_journal_dir_rejected_without_chmod(self):
        fake_dir = os.path.join(self.journal_dir, "foreign_dir")
        os.makedirs(fake_dir, mode=0o755)

        runner = self.make_runner(journal_dir=fake_dir)
        orig_lstat = os.lstat
        def fake_lstat(path, *args, **kwargs):
            st = orig_lstat(path, *args, **kwargs)
            if os.path.abspath(path) == os.path.abspath(fake_dir):
                return os.stat_result((st.st_mode, st.st_ino, st.st_dev, st.st_nlink, 99999, st.st_gid, st.st_size, st.st_atime, st.st_mtime, st.st_ctime))
            return st

        import unittest.mock
        with unittest.mock.patch("os.lstat", side_effect=fake_lstat):
            with unittest.mock.patch("os.chmod") as mock_chmod:
                with self.assertRaises(QASecurityError):
                    runner.sync_journal()
                mock_chmod.assert_not_called()

    def test_destination_file_hardlink_or_symlink_rejected(self):
        runner = self.make_runner()
        runner.sync_journal()
        path = runner.get_journal_path()
        hl_path = os.path.join(self.journal_dir, "hardlink_target.json")
        os.link(path, hl_path)
        with self.assertRaises(QASecurityError):
            runner.sync_journal()
        os.unlink(hl_path)

        os.unlink(path)
        dummy = os.path.join(self.journal_dir, "dummy.json")
        with open(dummy, "w") as f:
            f.write("{}")
        os.symlink(dummy, path)
        with self.assertRaises(QASecurityError):
            runner.sync_journal()

    def test_temp_collision_preexisting_bytes_preserved(self):
        runner = self.make_runner()
        jpath = runner.get_journal_path()
        import unittest.mock
        with unittest.mock.patch("secrets.token_hex", return_value="c0111de"):
            tmp_path = f"{jpath}.tmp.{os.getpid()}.c0111de"
            with open(tmp_path, "w", encoding="utf-8") as f:
                f.write("COLLISION_PREEXISTING_SECRET_DATA")

            with self.assertRaises(OSError):
                runner.sync_journal()

            self.assertTrue(os.path.exists(tmp_path))
            with open(tmp_path, "r", encoding="utf-8") as f:
                self.assertEqual(f.read(), "COLLISION_PREEXISTING_SECRET_DATA")
            os.unlink(tmp_path)

    def test_os_replace_fault_preserves_old_file(self):
        runner = self.make_runner()
        runner.journal["nonce"] = runner.nonce
        runner.sync_journal()
        jpath = runner.get_journal_path()
        with open(jpath, "r", encoding="utf-8") as f:
            initial_content = f.read()

        import unittest.mock
        with unittest.mock.patch("os.replace", side_effect=OSError("Replace fault")):
            with self.assertRaises(OSError):
                runner.journal["nonce"] = "faulty_replace_attempt"
                runner.sync_journal()

        with open(jpath, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), initial_content)

    def test_postcommit_dirfsync_failure_raises_uncertain_and_latches(self):
        runner = self.make_runner()
        runner.journal["nonce"] = runner.nonce
        runner.sync_journal()
        jpath = runner.get_journal_path()

        runner.journal["nonce"] = "postcommit_updated_nonce"

        real_fsync = os.fsync
        def failing_dir_fsync(fd):
            st = os.fstat(fd)
            if stat.S_ISDIR(st.st_mode):
                raise OSError("Dir fsync IO error")
            return real_fsync(fd)

        import unittest.mock
        with unittest.mock.patch("os.fsync", side_effect=failing_dir_fsync):
            with self.assertRaisesRegex(QASecurityError, "ERR_JOURNAL_DURABILITY_UNCERTAIN"):
                runner.sync_journal()

        with open(jpath, "r", encoding="utf-8") as f:
            disk_content = f.read()
        self.assertIn("postcommit_updated_nonce", disk_content)

        self.assertTrue(runner._domain_mutation_stopped)
        self.assertEqual(runner.blocked_reason, "ERR_JOURNAL_DURABILITY_UNCERTAIN")
        with self.assertRaisesRegex(QASecurityError, "ERR_JOURNAL_DURABILITY_UNCERTAIN"):
            runner.guarded_request("PATCH", "/api/admin/tasks/1", body={"name_pt": "blocked"})

    def test_postcommit_dirfsync_during_registration_blocks_authority_and_records_obligation(self):
        transport = FakeHTTPTransport()
        runner = self.make_runner(transport=transport)
        runner.admin_token = "admin-token-123"

        transport.register_response(
            "GET",
            "/api/admin/categories",
            200,
            {"categories": [{"id": 303, "name_pt": f"qa_cat_{runner.nonce}", "parent_category_id": None}]},
        )

        real_fsync = os.fsync
        def failing_dir_fsync(fd):
            st = os.fstat(fd)
            if stat.S_ISDIR(st.st_mode):
                raise OSError("Dir fsync IO error")
            return real_fsync(fd)

        import unittest.mock
        with unittest.mock.patch("os.fsync", side_effect=failing_dir_fsync):
            with self.assertRaisesRegex(QASecurityError, "ERR_JOURNAL_DURABILITY_UNCERTAIN"):
                runner.revalidate_resource("categories", 303)

        self.assertNotIn(303, runner._active_verified_ownership.get("categories", {}))
        self.assertIsNone(runner.category_id)
        obligations = [o for o in runner.pending_cleanup_obligations if o.get("id") == 303]
        self.assertEqual(len(obligations), 1)
        self.assertTrue(runner._domain_mutation_stopped)


if __name__ == "__main__":
    unittest.main()
