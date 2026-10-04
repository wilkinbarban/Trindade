#!/usr/bin/env python3
"""Production Admin QA Verification Script.

Narrow, Python-standard-library-only QA automation tool for Trindade.
Safely exercises reversible production administration workflows under strict isolation:
- Default DRY-RUN mode; requires explicit --execute-production to run live.
- Fixed verified HTTPS origin: https://trindademasas.duckdns.org (redirects/arbitrary origins strictly refused).
- Passwords and tokens kept in-memory only; masked from all argv/output/raw errors.
- Strict prohibition of drivers POST/PUT/DELETE, time-slots mutations, and report/loading modifications.
- Isolated namespace via unique nonce; journal persisted in 0600 file outside Git.
- Verification via GET before DELETE; ensures category contains no non-owned tasks before cascade.
- Reverse cleanup (tasks -> category -> vehicle -> worker -> logout) on both success and mid-run failure.
- Uncertain POST requests are never automatically retried.
"""

from __future__ import annotations

import argparse
import copy
import http.client
import json
import os
import re
import secrets
import socket
import ssl
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ALLOWED_ORIGIN = "https://trindademasas.duckdns.org"
DEFAULT_AUTH_CONFIG = os.path.expanduser("~/.config/trindade/qa-auth.json")
DEFAULT_JOURNAL_DIR = os.path.expanduser("~/.config/trindade/qa-runs")

# Patterns for masking credentials and tokens in logs / output
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_\-\.]+")
PASSWORD_PATTERN = re.compile(r'(password["\']?\s*[:= ]\s*["\']?)([^\s,"\'&]+)(["\']?)', re.IGNORECASE)
TOKEN_PATTERN = re.compile(r'(token["\']?\s*[:= ]\s*["\']?)([^\s,"\'&]+)(["\']?)', re.IGNORECASE)
BEARER_PATTERN = re.compile(r"(Bearer\s+)[A-Za-z0-9_\-\.]+", re.IGNORECASE)

APPROVED_ERROR_CODES: Set[str] = {
    "ERR_REDIRECT_REFUSED",
    "ERR_JOURNAL_SYMLINK",
    "ERR_JOURNAL_DIR",
    "ERR_JOURNAL_PERMS",
    "ERR_JOURNAL_FILE",
    "ERR_JOURNAL_HARDLINK",
    "ERR_JOURNAL_DURABILITY_UNCERTAIN",
    "ERR_POST_CREATION_UNVERIFIED",
    "ERR_INVALID_ID",
    "ERR_TASK_NO_ACTIVE_CATEGORY",
    "ERR_TASK_OWNERSHIP",
    "ERR_TASK_CATEGORY_MISMATCH",
    "ERR_DELETE_FAILED",
    "ERR_CATEGORIES_MALFORMED",
    "ERR_CATEGORIES_STATUS",
    "ERR_TASKS_STATUS",
    "ERR_TASKS_MALFORMED",
    "ERR_CATEGORY_MISMATCH",
    "ERR_CATEGORY_OWNERSHIP",
    "ERR_CATEGORY_CHILD_REFERENCE",
    "ERR_TASK_FOREIGN",
    "ERR_CATEGORY_HAS_REMAINING_TASKS",
    "ERR_RESIDUAL_DETECTED_POST_CLEANUP",
    "ERR_CLEANUP_RESIDUALS_EXIST",
    "ERR_NETWORK_TRANSPORT",
    "ERR_DISCOVERY_SCHEMA",
    "ERR_POST_CLEANUP_SCHEMA",
    "ERR_DISCOVERY_FAILED",
    "ERR_POST_CLEANUP_FAILED",
    "ERR_CLEANUP_FAILED",
    "ERR_SECURITY_VIOLATION",
    "ERR_INTERNAL_ERROR",
}

BOUNDED_STATUS_ERROR_PATTERN = re.compile(
    r"^(ERR_(?:DISCOVERY_STATUS|POST_CLEANUP_CHECK_STATUS|LOGOUT_STATUS)_([1-5]\d{2}))(?::.*)?$"
)


def mask_secrets(text: str) -> str:
    """Masks JWT tokens, passwords, and authorization headers from text."""
    if not isinstance(text, str):
        text = str(text)
    masked = BEARER_PATTERN.sub(r"\1[REDACTED]", text)
    masked = JWT_PATTERN.sub("[REDACTED]", masked)
    masked = PASSWORD_PATTERN.sub(r"\1[REDACTED]\3", masked)
    masked = TOKEN_PATTERN.sub(r"\1[REDACTED]\3", masked)
    return masked


def sanitize_error_code(exc_or_msg: Any) -> str:
    """Sanitizes any error or exception down to an approved fixed code or bounded status code.

    Enforces:
    - Finite approved fixed codes only; arbitrary ERR_... codes are suppressed.
    - Preserves ONLY the code, never payload suffixes or dynamic text.
    - HTTP status codes bounded to [100..599] with no payload.
    - Unknown network errors map to ERR_NETWORK_TRANSPORT.
    - Unknown security violations map to ERR_SECURITY_VIOLATION.
    - All other unknown errors map to ERR_INTERNAL_ERROR.
    - Must never throw at error boundary; fallback fixed code even for unexpected types.
    """
    try:
        if isinstance(exc_or_msg, (urllib.error.URLError, socket.error)):
            return "ERR_NETWORK_TRANSPORT"

        if exc_or_msg is None:
            return "ERR_INTERNAL_ERROR"

        msg = str(exc_or_msg).strip()

        # Check bounded status codes first (e.g. ERR_DISCOVERY_STATUS_500)
        status_match = BOUNDED_STATUS_ERROR_PATTERN.match(msg)
        if status_match:
            return status_match.group(1)

        # Check for structured code at start of message (e.g. ERR_REDIRECT_REFUSED: ...)
        m = re.match(r"^([A-Z0-9_]+)", msg)
        if m:
            candidate_code = m.group(1)
            if candidate_code in APPROVED_ERROR_CODES:
                return candidate_code

        # Check if this was a Network transport error string
        if msg.startswith("Network transport error") or "network" in msg.lower() or "connection" in msg.lower() or "timeout" in msg.lower():
            return "ERR_NETWORK_TRANSPORT"

        # Check if this was a QASecurityError or security-related error
        if isinstance(exc_or_msg, QASecurityError) or "security" in msg.lower() or "forbidden" in msg.lower() or "refused" in msg.lower():
            return "ERR_SECURITY_VIOLATION"

        return "ERR_INTERNAL_ERROR"
    except Exception:
        return "ERR_INTERNAL_ERROR"


class QASecurityError(Exception):
    """Raised when an origin, redirect, endpoint, or foreign-data invariant is violated."""


class QACleanupResidualError(Exception):
    """Raised when reverse cleanup encounters residuals or foreign data references."""


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Refuses any HTTP redirect to prevent silent routing changes or origin leakage."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise QASecurityError(
            f"ERR_REDIRECT_REFUSED: HTTP Redirect ({code}) detected and strictly refused."
        )


class HTTPTransport:
    """Production HTTPS transport enforcing origin locks, no-redirects, and timeout limits."""

    def __init__(self, origin: str = ALLOWED_ORIGIN, timeout: int = 15):
        if origin != ALLOWED_ORIGIN:
            raise QASecurityError(f"ERR_SECURITY_VIOLATION: Origin is forbidden. Must be '{ALLOWED_ORIGIN}'.")
        self.origin = origin
        self.timeout = timeout
        self.ssl_context = ssl.create_default_context()
        self.opener = urllib.request.build_opener(NoRedirectHandler)

    def request(
        self,
        method: str,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[Dict[str, Any]] = None,
    ) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
        if not path.startswith("/"):
            path = "/" + path
        url = self.origin + path

        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https":
            raise QASecurityError(f"Insecure scheme '{parsed.scheme}' refused; HTTPS required.")
        if f"{parsed.scheme}://{parsed.netloc}" != self.origin:
            raise QASecurityError(f"Host mismatch in URL '{url}'; only '{self.origin}' allowed.")

        req_headers = {
            "Accept": "application/json",
            "User-Agent": "Trindade-Production-QA/1.0",
        }
        if headers:
            req_headers.update(headers)

        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            req_headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=req_headers, method=method)

        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                status = resp.status
                resp_headers = dict(resp.headers)
                raw_content = resp.read().decode("utf-8", errors="replace")
                try:
                    resp_json = json.loads(raw_content) if raw_content else {}
                except json.JSONDecodeError:
                    resp_json = {"raw": raw_content}
                return status, resp_json, resp_headers
        except urllib.error.HTTPError as e:
            status = e.code
            resp_headers = dict(e.headers)
            raw_content = e.read().decode("utf-8", errors="replace")
            try:
                resp_json = json.loads(raw_content) if raw_content else {}
            except json.JSONDecodeError:
                resp_json = {"raw": raw_content}
            return status, resp_json, resp_headers
        except urllib.error.URLError:
            raise Exception("ERR_NETWORK_TRANSPORT") from None


class FakeHTTPTransport(HTTPTransport):
    """In-memory mock transport for non-network testing and strict scenario contracts."""

    def __init__(self, origin: str = ALLOWED_ORIGIN, default_nonce: str = "run12345"):
        super().__init__(origin=origin)
        self.default_nonce = default_nonce
        self.history: List[Dict[str, Any]] = []
        self.routes: Dict[Tuple[str, str], Tuple[int, Dict[str, Any], Dict[str, str]]] = {}
        self.errors: Dict[Tuple[str, str], Exception] = {}
        self.custom_handlers: List[Any] = []
        self.dynamic_state: Dict[str, Any] = {
            "users": [],
            "categories": [],
            "tasks": [],
            "vehicles": [],
        }

    def register_response(
        self,
        method: str,
        path: str,
        status: int = 200,
        body: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
    ):
        self.routes[(method.upper(), path)] = (status, body or {}, headers or {})

    def register_error(self, method: str, path: str, error: Exception):
        self.errors[(method.upper(), path)] = error

    def register_handler(self, handler):
        self.custom_handlers.append(handler)

    def _remove_dynamic(self, path: str):
        m = re.match(r"^/api/admin/(categories|tasks|vehicles|users)/(\d+)$", path)
        if m:
            coll, id_str = m.group(1), m.group(2)
            item_id = int(id_str)
            if coll in self.dynamic_state:
                self.dynamic_state[coll] = [
                    item for item in self.dynamic_state[coll] if item.get("id") != item_id
                ]

    def request(
        self,
        method: str,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[Dict[str, Any]] = None,
    ) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
        method_up = method.upper()
        self.history.append({"method": method_up, "path": path, "headers": headers, "body": body})

        # Check for registered errors
        if (method_up, path) in self.errors:
            raise self.errors[(method_up, path)]

        # Custom handlers (inspect method, path, headers, body)
        for handler in self.custom_handlers:
            res = handler(method_up, path, headers or {}, body or {})
            if res is not None:
                status, res_body, res_headers = res
                if status in (301, 302, 303, 307, 308) or "Location" in res_headers:
                    raise QASecurityError(f"ERR_REDIRECT_REFUSED: HTTP Redirect ({status}) detected and strictly refused.")
                if method_up == "DELETE" and status in (200, 204):
                    self._remove_dynamic(path)
                return status, res_body, res_headers

        # Check for exact route matches
        if (method_up, path) in self.routes:
            status, res_body, res_headers = self.routes[(method_up, path)]
            if status in (301, 302, 303, 307, 308) or "Location" in res_headers:
                raise QASecurityError(f"ERR_REDIRECT_REFUSED: HTTP Redirect ({status}) detected and strictly refused.")
            if method_up == "DELETE" and status in (200, 204):
                self._remove_dynamic(path)
            return status, res_body, res_headers

        # Fallback dynamic handling for mock scenarios
        status, res_body, res_headers = self._handle_dynamic(method_up, path, headers or {}, body or {})
        if method_up == "DELETE" and status in (200, 204):
            self._remove_dynamic(path)
        return status, res_body, res_headers

    def _handle_dynamic(
        self, method: str, path: str, headers: Dict[str, str], body: Dict[str, Any]
    ) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
        # Admin / Worker lists
        if method == "GET" and path == "/api/admin/categories":
            return 200, {"categories": self.dynamic_state["categories"]}, {}
        if method == "GET" and path == "/api/admin/tasks":
            return 200, {"tasks": self.dynamic_state["tasks"]}, {}
        if method == "GET" and path == "/api/admin/vehicles":
            auth = headers.get("Authorization", "")
            if "worker" in auth:
                return 403, {"error": "Insufficient permissions"}, {}
            return 200, {"vehicles": self.dynamic_state["vehicles"]}, {}
        if method == "GET" and path == "/api/admin/users":
            auth = headers.get("Authorization", "")
            if "worker" in auth:
                return 403, {"error": "Insufficient permissions"}, {}
            return 200, {"users": self.dynamic_state["users"]}, {}

        # Default 200
        return 200, {"success": True}, {}

    def setup_scenario_mocks(self, runner: ProductionQARunner):
        """Sets up mock responses for the complete production QA scenario."""
        nonce = runner.nonce
        worker_id = 201
        cat_id = 301
        admin_task_id = 401
        worker_task_id = 402
        veh_id = 501
        plate = f"QA{nonce[:5].upper()}"

        worker_active = True

        def scenario_handler(method: str, path: str, headers: Dict[str, str], body: Dict[str, Any]):
            nonlocal worker_active
            auth = headers.get("Authorization", "")

            # Login routing
            if method == "POST" and path == "/api/auth/login":
                username = body.get("username", "")
                if f"qa_worker_{nonce}" in username:
                    return 200, {
                        "token": f"worker_mock_token_{nonce}",
                        "refreshToken": f"worker_mock_refresh_{nonce}",
                        "user": {"id": worker_id, "username": username, "role": "Trabalhador"},
                    }, {}
                return 200, {
                    "token": f"admin_mock_token_{nonce}",
                    "refreshToken": f"admin_mock_refresh_{nonce}",
                    "user": {"id": 1, "username": "admin", "role": "Administrador"},
                }, {}

            # Task creation routing (admin vs worker)
            if method == "POST" and path == "/api/admin/tasks":
                is_worker = f"worker_mock_token_{nonce}" in auth or "worker" in body.get("name_pt", "")
                tid = worker_task_id if is_worker else admin_task_id
                uid = worker_id if is_worker else 1
                return 201, {
                    "task": {
                        "id": tid,
                        "category_id": cat_id,
                        "name_pt": body.get("name_pt"),
                        "created_by_user_id": uid,
                    }
                }, {}

            # Worker authorization boundaries
            if f"worker_mock_token_{nonce}" in auth:
                # Worker editing own task
                if method == "PATCH" and path == f"/api/admin/tasks/{worker_task_id}":
                    if "is_active" in body:
                        return 403, {"error": "Insufficient permissions"}, {}
                    return 200, {"task": {"id": worker_task_id, "name_pt": body.get("name_pt")}}, {}
                # Worker editing admin task
                if method == "PATCH" and path == f"/api/admin/tasks/{admin_task_id}":
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker deleting admin task
                if method == "DELETE" and path == f"/api/admin/tasks/{admin_task_id}":
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker category harmless patch probe
                if method == "PATCH" and path.startswith("/api/admin/categories/"):
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker category write
                if method == "POST" and path == "/api/admin/categories":
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker vehicles read
                if method == "GET" and path == "/api/admin/vehicles":
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker users read
                if method == "GET" and path == "/api/admin/users":
                    return 403, {"error": "Insufficient permissions"}, {}
                # Worker profile
                if method == "GET" and path == "/api/auth/profile":
                    if not worker_active:
                        return 401, {"error": "Invalid or expired token"}, {}
                    return 200, {"user": {"id": worker_id, "username": f"qa_worker_{nonce}"}}, {}

            # Admin deactivating worker
            if method == "PATCH" and path == f"/api/admin/users/{worker_id}":
                if body.get("is_active") == 0:
                    worker_active = False
                return 200, {"user": {"id": worker_id, "is_active": body.get("is_active", 1)}}, {}

            return None

        self.register_handler(scenario_handler)

        # Worker creation
        self.dynamic_state["users"].append(
            {
                "id": worker_id,
                "username": f"qa_worker_{nonce}",
                "display_name": f"QA Worker {nonce}",
                "role_id": 2,
                "role_name": "Trabalhador",
                "is_active": 1,
            }
        )
        self.register_response(
            "POST",
            "/api/admin/users",
            201,
            {
                "user": {
                    "id": worker_id,
                    "username": f"qa_worker_{nonce}",
                    "display_name": f"QA Worker {nonce}",
                    "role_id": 2,
                    "role_name": "Trabalhador",
                    "is_active": 1,
                }
            },
        )

        # Category creation
        self.dynamic_state["categories"].append(
            {
                "id": cat_id,
                "name_pt": f"qa_cat_{nonce}",
                "name_es": f"qa_cat_{nonce}",
                "category_type": "check",
                "sort_order": 999,
                "parent_category_id": None,
            }
        )
        self.register_response(
            "POST",
            "/api/admin/categories",
            201,
            {
                "category": {
                    "id": cat_id,
                    "name_pt": f"qa_cat_{nonce}",
                    "name_es": f"qa_cat_{nonce}",
                    "category_type": "check",
                    "sort_order": 999,
                }
            },
        )

        # Admin Task creation
        self.dynamic_state["tasks"].append(
            {
                "id": admin_task_id,
                "category_id": cat_id,
                "name_pt": f"qa_task_admin_{nonce}",
                "created_by_user_id": 1,
                "is_active": 1,
            }
        )

        # Worker Task creation
        self.dynamic_state["tasks"].append(
            {
                "id": worker_task_id,
                "category_id": cat_id,
                "name_pt": f"qa_task_worker_{nonce}",
                "created_by_user_id": worker_id,
                "is_active": 1,
            }
        )

        # Admin safe edits
        self.register_response("PATCH", f"/api/admin/tasks/{admin_task_id}", 200, {"task": {"id": admin_task_id}})
        self.register_response("PATCH", f"/api/admin/categories/{cat_id}", 200, {"category": {"id": cat_id}})

        # Vehicle lifecycle
        self.dynamic_state["vehicles"].append(
            {
                "id": veh_id,
                "description": f"qa_vehicle_{nonce}",
                "license_plate": plate,
                "is_active": 1,
            }
        )
        self.register_response(
            "POST",
            "/api/admin/vehicles",
            201,
            {
                "vehicle": {
                    "id": veh_id,
                    "description": f"qa_vehicle_{nonce}",
                    "license_plate": plate,
                    "is_active": 1,
                }
            },
        )
        self.register_response("PATCH", f"/api/admin/vehicles/{veh_id}", 200, {"vehicle": {"id": veh_id}})
        self.register_response("DELETE", f"/api/admin/vehicles/{veh_id}", 200, {"success": True})

        # Cleanups
        self.register_response("DELETE", f"/api/admin/tasks/{worker_task_id}", 200, {"success": True})
        self.register_response("DELETE", f"/api/admin/tasks/{admin_task_id}", 200, {"success": True})
        self.register_response("DELETE", f"/api/admin/categories/{cat_id}", 200, {"success": True})
        self.register_response("DELETE", f"/api/admin/users/{worker_id}", 200, {"success": True})
        self.register_response("POST", "/api/auth/logout", 200, {"success": True})


class ProductionQARunner:
    """Orchestrates the narrow reversible production QA workflow."""

    def __init__(
        self,
        origin: str = ALLOWED_ORIGIN,
        execute_production: bool = False,
        config_path: str = DEFAULT_AUTH_CONFIG,
        journal_dir: str = DEFAULT_JOURNAL_DIR,
        nonce: Optional[str] = None,
        transport: Optional[HTTPTransport] = None,
        admin_credentials: Optional[Dict[str, str]] = None,
    ):
        self.origin = origin
        self.execute_production = execute_production
        self.is_dry_run = not execute_production
        self.config_path = config_path
        self.journal_dir = journal_dir
        self.nonce = nonce or str(uuid.uuid4())
        self.run_id = f"qa-run-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{self.nonce}"
        self.transport = transport or HTTPTransport(origin=self.origin)
        self._admin_credentials = admin_credentials  # In-memory only

        # Runtime state
        self.admin_token: Optional[str] = None
        self.admin_refresh_token: Optional[str] = None
        self.admin_user_id: Optional[int] = None

        self.worker_token: Optional[str] = None
        self.worker_refresh_token: Optional[str] = None
        self.worker_password: Optional[str] = None
        self.worker_id: Optional[int] = None

        self.category_id: Optional[int] = None
        self.admin_task_id: Optional[int] = None
        self.worker_task_id: Optional[int] = None
        self.vehicle_id: Optional[int] = None

        self.pending_cleanup_obligations: List[Dict[str, Any]] = []
        self._domain_mutation_stopped = False
        self.blocked_reason: Optional[str] = None

        # Journal tracking
        # Note on limitations: Persisted journals are untrusted JSON files on disk without
        # cryptographic signatures; they can be corrupted or stale. Therefore, active
        # in-memory verified ownership is strictly separated from persisted journals.
        self.journal: Dict[str, Any] = {
            "run_id": self.run_id,
            "nonce": self.nonce,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "execute_production": self.execute_production,
            "resources": {
                "worker_user": None,
                "category": None,
                "tasks": [],
                "vehicle": None,
            },
            "cleaned_up": [],
            "residuals": [],
            "pending_cleanup_obligations": [],
            "status": "initialized",
        }

        # Active verified session-owned resources registry (in-memory only).
        # Persisted journals and caller attribute mutations are untrusted.
        # Only successful guarded POST responses with validated positive integer IDs
        # or explicit test provenance are tracked here.
        self._active_verified_ownership: Dict[str, Dict[int, Dict[str, Any]]] = {
            "categories": {},
            "tasks": {},
            "vehicles": {},
            "users": {},
        }

    def validate_origin(self):
        if self.origin != ALLOWED_ORIGIN:
            raise QASecurityError(
                f"ERR_SECURITY_VIOLATION: Origin is forbidden. Fixed origin '{ALLOWED_ORIGIN}' is required."
            )

    def is_owned_label(self, entity_type: str, label: str) -> bool:
        """Validates if a given label matches the exact expected session fixture pattern."""
        if not isinstance(label, str):
            return False
        if entity_type == "category":
            return label in (f"qa_cat_{self.nonce}", f"qa_cat_edit_{self.nonce}")
        elif entity_type == "task":
            return label in (
                f"qa_task_admin_{self.nonce}",
                f"qa_task_admin_edit_{self.nonce}",
                f"qa_task_worker_{self.nonce}",
                f"qa_task_worker_edit_{self.nonce}",
            )
        elif entity_type == "vehicle":
            return label in (f"qa_vehicle_{self.nonce}", f"qa_vehicle_{self.nonce}_updated")
        elif entity_type in ("worker_user", "users"):
            return label == f"qa_worker_{self.nonce}"
        return False

    def _is_valid_collection_entry(self, coll: str, item: Any) -> bool:
        """Validates that a collection item strictly conforms to existing wire contract schema.

        Requires:
        - Must be a dictionary.
        - Strict positive integer ID (not bool).
        - Required payload keys and expected types per collection:
          * categories: name_pt (str), parent_category_id (if present: None or positive int, not bool)
          * tasks: name_pt (str), category_id (positive int, not bool)
          * vehicles: description (str), license_plate (str)
          * users: username (str), role_id (positive int, not bool)
        """
        if not isinstance(item, dict):
            return False

        item_id = item.get("id")
        if type(item_id) is not int or isinstance(item_id, bool) or item_id <= 0:
            return False

        if coll in ("categories", "category"):
            if not isinstance(item.get("name_pt"), str):
                return False
            if "parent_category_id" in item:
                pid = item["parent_category_id"]
                if pid is not None and (type(pid) is not int or isinstance(pid, bool) or pid <= 0):
                    return False
            return True

        elif coll in ("tasks", "task"):
            if not isinstance(item.get("name_pt"), str):
                return False
            cid = item.get("category_id")
            if type(cid) is not int or isinstance(cid, bool) or cid <= 0:
                return False
            return True

        elif coll in ("vehicles", "vehicle"):
            if not isinstance(item.get("description"), str):
                return False
            if not isinstance(item.get("license_plate"), str):
                return False
            return True

        elif coll in ("users", "worker_user", "user"):
            if not isinstance(item.get("username"), str):
                return False
            role_id = item.get("role_id")
            if type(role_id) is not int or isinstance(role_id, bool) or role_id <= 0:
                return False
            return True

        return False

    def revalidate_resource(
        self,
        collection: str,
        item_id: int,
        headers: Optional[Dict[str, str]] = None,
    ) -> bool:
        """Safe GET read-only verification of exact labels, roles, and relationships
        via existing transport before registering in active verified ownership.
        Persisted journals and caller attributes are untrusted.
        """
        if type(item_id) is not int or item_id <= 0:
            return False

        auth_headers = dict(headers) if headers else {}
        if "Authorization" not in auth_headers and self.admin_token:
            auth_headers["Authorization"] = f"Bearer {self.admin_token}"

        coll_map = {
            "category": "categories",
            "categories": "categories",
            "task": "tasks",
            "tasks": "tasks",
            "vehicle": "vehicles",
            "vehicles": "vehicles",
            "worker_user": "users",
            "users": "users",
        }
        target_coll = coll_map.get(collection, collection)
        if target_coll not in self._active_verified_ownership:
            return False

        # Transactional snapshot: restore exact preexisting state if persist fails
        prev_ownership = copy.deepcopy(self._active_verified_ownership)
        prev_category_id = self.category_id
        prev_admin_task_id = self.admin_task_id
        prev_worker_task_id = self.worker_task_id
        prev_vehicle_id = self.vehicle_id
        prev_worker_id = self.worker_id
        prev_resources = copy.deepcopy(self.journal.get("resources", {}))

        try:
            status, body, _ = self.transport.request("GET", f"/api/admin/{target_coll}", headers=auth_headers, body=None)
            if status != 200 or not isinstance(body, dict):
                return False

            items = body.get(target_coll, [])
            if not isinstance(items, list):
                return False

            matched = next((item for item in items if isinstance(item, dict) and type(item.get("id")) is int and item.get("id") == item_id and item.get("id") > 0), None)
            if not matched:
                return False

            if target_coll == "categories":
                name_pt = matched.get("name_pt")
                if not self.is_owned_label("category", name_pt):
                    return False
                if "parent_category_id" not in matched or matched["parent_category_id"] is not None:
                    return False
                self._active_verified_ownership["categories"][item_id] = {"name_pt": name_pt}
                if self.category_id is None:
                    self.category_id = item_id
                self.journal_record_resource("category", item_id, name_pt)
                return True

            elif target_coll == "tasks":
                name_pt = matched.get("name_pt")
                if not self.is_owned_label("task", name_pt):
                    return False
                if "category_id" not in matched:
                    return False
                cat_id = matched["category_id"]
                if type(cat_id) is not int or cat_id <= 0 or cat_id not in self._get_tracked_ids("categories"):
                    return False
                self._active_verified_ownership["tasks"][item_id] = {"name_pt": name_pt, "category_id": cat_id}
                if "worker" in name_pt:
                    if self.worker_task_id is None:
                        self.worker_task_id = item_id
                else:
                    if self.admin_task_id is None:
                        self.admin_task_id = item_id
                self.journal_record_resource("task", item_id, name_pt)
                return True

            elif target_coll == "vehicles":
                desc = matched.get("description")
                plate = matched.get("license_plate")
                if not self.is_owned_label("vehicle", desc):
                    return False
                if plate != f"QA{self.nonce[:5].upper()}":
                    return False
                self._active_verified_ownership["vehicles"][item_id] = {"description": desc, "license_plate": plate}
                if self.vehicle_id is None:
                    self.vehicle_id = item_id
                self.journal_record_resource("vehicle", item_id, plate)
                return True

            elif target_coll == "users":
                if item_id == 1 or (self.admin_user_id is not None and item_id == self.admin_user_id):
                    return False
                username = matched.get("username")
                if "role_id" not in matched:
                    return False
                role_id = matched["role_id"]
                if not self.is_owned_label("worker_user", username):
                    return False
                if type(role_id) is not int or role_id != 2:
                    return False
                self._active_verified_ownership["users"][item_id] = {"username": username, "role_id": 2}
                if self.worker_id is None:
                    self.worker_id = item_id
                self.journal_record_resource("worker_user", item_id, username)
                return True

        except Exception as e:
            # Transaction rollback: restore preexisting state on any failure during registration/persistence
            self._active_verified_ownership = prev_ownership
            self.category_id = prev_category_id
            self.admin_task_id = prev_admin_task_id
            self.worker_task_id = prev_worker_task_id
            self.vehicle_id = prev_vehicle_id
            self.worker_id = prev_worker_id
            self.journal["resources"] = prev_resources
            if isinstance(e, QASecurityError) and "ERR_JOURNAL_DURABILITY_UNCERTAIN" in str(e):
                self._trip_domain_mutation_latch("ERR_JOURNAL_DURABILITY_UNCERTAIN")
                obligation = {"entity_type": target_coll, "id": item_id, "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                raise
            return False

        return False

    def _get_tracked_ids(self, collection: str) -> set[int]:
        """Returns the set of active verified resource IDs for the given collection.

        Separates untrusted persisted journal / discovery from active verified ownership.
        Persisted journals cannot be cryptographically trusted; writes require active session proof.
        """
        coll_map = {
            "category": "categories",
            "categories": "categories",
            "task": "tasks",
            "tasks": "tasks",
            "vehicle": "vehicles",
            "vehicles": "vehicles",
            "worker_user": "users",
            "users": "users",
        }
        target_coll = coll_map.get(collection, collection)
        return set(self._active_verified_ownership.get(target_coll, {}).keys())

    def _trip_domain_mutation_latch(self, reason: str):
        self._domain_mutation_stopped = True
        self.blocked_reason = reason

    def assert_safe_request(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
    ):
        method_up = method.upper()

        # Normalize path
        if not path.startswith("/"):
            path = "/" + path

        # Reject encoded/ambiguous paths before transport
        if (
            any(c in path for c in ("%", "\\", "?", "#"))
            or "//" in path
            or "/../" in path
            or path.endswith("/..")
            or "/./" in path
            or path.endswith("/.")
        ):
            raise QASecurityError(f"Ambiguous or encoded path strictly refused: '{path}'")

        # A failed creation proof/durability permanently stops domain mutations.
        # Only the existing exact POST logout route may close our own session;
        # login and every domain write remain blocked. Read-only investigation
        # never resets the latch or turns a cleanup obligation into authority.
        if self._domain_mutation_stopped and method_up in ("POST", "PUT", "PATCH", "DELETE"):
            if not (method_up == "POST" and path == "/api/auth/logout"):
                err_code = self.blocked_reason or "ERR_POST_CREATION_UNVERIFIED"
                raise QASecurityError(err_code)

        # Explicit forbidden route checks (preserve existing security invariants & error messages)
        if "/drivers" in path and method_up in ("POST", "PUT", "PATCH", "DELETE"):
            raise QASecurityError(f"Drivers mutation is strictly forbidden on production: {method_up} {path}")

        if "/time-slots" in path and method_up in ("POST", "PUT", "PATCH", "DELETE"):
            raise QASecurityError(f"Time-slots mutation is strictly forbidden on production: {method_up} {path}")

        if (path.startswith("/api/reports") or path.startswith("/api/loading")) and method_up in (
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
        ):
            raise QASecurityError(f"Reports/loading mutation is strictly forbidden: {method_up} {path}")

        if path == "/api/auth/profile" and method_up in ("PATCH", "PUT", "POST", "DELETE"):
            raise QASecurityError("Mutating original account profile is strictly forbidden.")

        if path == "/api/auth/change-password" and method_up == "POST":
            raise QASecurityError("Changing account password via self-service is strictly forbidden.")

        if (
            path.startswith("/api/auth/")
            and path not in ("/api/auth/login", "/api/auth/logout")
            and method_up in ("POST", "PUT", "PATCH", "DELETE")
        ):
            raise QASecurityError(f"Auth/account mutation strictly forbidden: {method_up} {path}")

        # Preserve current authorized reads (GET/HEAD)
        if method_up in ("GET", "HEAD"):
            return

        # Positive write allowlist: all write mutations require explicit allowlist match
        if method_up not in ("POST", "PATCH", "DELETE"):
            raise QASecurityError(f"Method '{method_up}' not permitted for write mutations: {path}")

        # 1. Auth lifecycle writes (POST only to exact login/logout routes)
        if path in ("/api/auth/login", "/api/auth/logout"):
            if method_up != "POST":
                raise QASecurityError(f"Method '{method_up}' not allowed on {path}")
            return

        # 2. Collection writes (POST only on disposable entities with validated session-owned payload)
        collection_post_routes = {
            "/api/admin/categories": "category",
            "/api/admin/tasks": "task",
            "/api/admin/vehicles": "vehicle",
            "/api/admin/users": "worker_user",
        }
        if path in collection_post_routes:
            if method_up != "POST":
                raise QASecurityError(f"Method '{method_up}' not allowed on collection endpoint '{path}'")

            entity_type = collection_post_routes[path]
            if not isinstance(body, dict):
                raise QASecurityError(f"POST {path} requires a valid JSON object payload")

            # Validate session-owned fixture payload
            if entity_type == "category":
                name_pt = body.get("name_pt")
                expected_cat = f"qa_cat_{self.nonce}"
                if name_pt != expected_cat:
                    raise QASecurityError(
                        f"Category create payload requires exact fixture label '{expected_cat}'; got '{name_pt}'"
                    )
                name_es = body.get("name_es")
                if name_es is not None and name_es != expected_cat:
                    raise QASecurityError(
                        f"Category create payload requires exact fixture name_es '{expected_cat}'; got '{name_es}'"
                    )
                if body.get("parent_category_id") is not None:
                    raise QASecurityError("Category create cannot specify foreign parent_category_id")
            elif entity_type == "task":
                name_pt = body.get("name_pt")
                expected_tasks = (f"qa_task_admin_{self.nonce}", f"qa_task_worker_{self.nonce}")
                if name_pt not in expected_tasks:
                    raise QASecurityError(
                        f"Task create payload requires exact session fixture label in {expected_tasks}; got '{name_pt}'"
                    )
                cat_id = body.get("category_id")
                tracked_cat_ids = self._get_tracked_ids("categories")
                if not isinstance(cat_id, int) or isinstance(cat_id, bool) or cat_id <= 0:
                    raise QASecurityError(f"Task create requires a positive integer category_id; got '{cat_id}'")
                if not tracked_cat_ids:
                    raise QASecurityError("Task create forbidden: no active verified QA category exists in session")
                if cat_id not in tracked_cat_ids:
                    raise QASecurityError(
                        f"Task create category_id {cat_id} does not match active verified QA category IDs {tracked_cat_ids}"
                    )
            elif entity_type == "vehicle":
                desc = body.get("description")
                plate = body.get("license_plate")
                expected_desc = f"qa_vehicle_{self.nonce}"
                expected_plate = f"QA{self.nonce[:5].upper()}"
                if desc != expected_desc or plate != expected_plate:
                    raise QASecurityError(
                        f"Vehicle create payload requires exact fixture description '{expected_desc}' "
                        f"and license_plate '{expected_plate}'; got desc='{desc}', plate='{plate}'"
                    )
            elif entity_type == "worker_user":
                username = body.get("username")
                expected_user = f"qa_worker_{self.nonce}"
                if username != expected_user:
                    raise QASecurityError(
                        f"Worker user create payload requires exact username '{expected_user}'; got '{username}'"
                    )
                role_id = body.get("role_id")
                if not (type(role_id) is int and role_id == 2):
                    raise QASecurityError(
                        f"Creating user with role_id {role_id} is forbidden. Only worker role (2) is allowed."
                    )
            return

        # 3. ID-targeted mutations (PATCH, DELETE only on disposable entities with active verified IDs)
        id_mutation_match = re.match(r"^/api/admin/(categories|tasks|vehicles|users)/([^/]+)$", path)
        if not id_mutation_match:
            raise QASecurityError(f"Unauthorized or unknown write route: {method_up} {path}")

        if method_up not in ("PATCH", "DELETE"):
            raise QASecurityError(f"Method '{method_up}' not allowed on resource endpoint '{path}'")

        coll_name = id_mutation_match.group(1)
        id_str = id_mutation_match.group(2)

        if not id_str.isdigit() or int(id_str) <= 0:
            raise QASecurityError(f"Invalid resource ID '{id_str}': positive integer required")

        item_id = int(id_str)

        # Users route: worker only matching new active owned record + verified role 2
        # Reject existing users, admin user (even if admin_user_id was not loaded), and unproven IDs
        if coll_name == "users":
            if item_id == 1 or (self.admin_user_id is not None and item_id == self.admin_user_id):
                raise QASecurityError(f"Mutating or deleting admin user {item_id} is strictly forbidden.")
            user_entry = self._active_verified_ownership.get("users", {}).get(item_id)
            if not user_entry or user_entry.get("role_id") != 2:
                raise QASecurityError(
                    f"User route only permits active verified worker (role 2). Target {item_id} rejected."
                )

        tracked_ids = self._get_tracked_ids(coll_name)
        if item_id not in tracked_ids:
            raise QASecurityError(
                f"Untracked or foreign {coll_name} ID {item_id} refused for {method_up}. "
                f"Active tracked IDs: {tracked_ids}"
            )

        if method_up == "PATCH":
            if body is None or not isinstance(body, dict):
                raise QASecurityError(f"PATCH {path} requires a valid JSON object payload")

            # Mutations PATCH payload cannot change ownership markers, category relations, or role away from 2
            if coll_name == "tasks":
                if "category_id" in body:
                    new_cat = body["category_id"]
                    tracked_cats = self._get_tracked_ids("categories")
                    if not isinstance(new_cat, int) or isinstance(new_cat, bool) or new_cat not in tracked_cats:
                        raise QASecurityError(
                            f"PATCH task cannot change category_id to foreign or untracked category: {new_cat}"
                        )
                if "name_pt" in body:
                    new_name = body["name_pt"]
                    expected_names = (
                        f"qa_task_admin_{self.nonce}",
                        f"qa_task_admin_edit_{self.nonce}",
                        f"qa_task_worker_{self.nonce}",
                        f"qa_task_worker_edit_{self.nonce}",
                    )
                    if new_name not in expected_names:
                        raise QASecurityError(
                            f"PATCH task cannot change name_pt to non-session fixture label: '{new_name}'"
                        )
            elif coll_name == "categories":
                if "parent_category_id" in body and body["parent_category_id"] is not None:
                    raise QASecurityError("PATCH category cannot change parent_category_id to foreign category")
                if "name_pt" in body:
                    new_name = body["name_pt"]
                    if new_name not in (f"qa_cat_{self.nonce}", f"qa_cat_edit_{self.nonce}"):
                        raise QASecurityError(
                            f"PATCH category cannot change name_pt to non-session fixture label: '{new_name}'"
                        )
            elif coll_name == "users":
                role_id = body.get("role_id")
                if "role_id" in body and not (type(role_id) is int and role_id == 2):
                    raise QASecurityError(f"PATCH user cannot change role away from 2; got role_id={role_id}")
                if "username" in body and body["username"] != f"qa_worker_{self.nonce}":
                    raise QASecurityError("PATCH user cannot change username away from session fixture")
            elif coll_name == "vehicles":
                if "license_plate" in body and body["license_plate"] != f"QA{self.nonce[:5].upper()}":
                    raise QASecurityError("PATCH vehicle cannot change license_plate")
                if "description" in body and body["description"] not in (f"qa_vehicle_{self.nonce}", f"qa_vehicle_{self.nonce}_updated"):
                    raise QASecurityError("PATCH vehicle cannot change description to foreign label")

    def _record_uncertain_cleanup_obligation(
        self,
        coll_name: str,
        entity_id: Optional[int] = None,
    ):
        self._trip_domain_mutation_latch("ERR_POST_CREATION_UNVERIFIED")
        obligation: Dict[str, Any] = {
            "entity_type": coll_name,
            "run_ref": self.nonce,
        }
        if type(entity_id) is int and entity_id > 0:
            obligation["id"] = entity_id

        if obligation not in self.pending_cleanup_obligations:
            self.pending_cleanup_obligations.append(obligation)
        if "pending_cleanup_obligations" not in self.journal:
            self.journal["pending_cleanup_obligations"] = []
        if obligation not in self.journal["pending_cleanup_obligations"]:
            self.journal["pending_cleanup_obligations"].append(obligation)
        try:
            self.sync_journal()
        except Exception:
            # Keep the minimal in-memory obligation without granting authority.
            pass
        raise QASecurityError("ERR_POST_CREATION_UNVERIFIED") from None

    def _register_created_post_resource(
        self,
        path: str,
        req_body: Optional[Dict[str, Any]],
        resp_body: Any,
        headers: Optional[Dict[str, str]] = None,
    ):
        route_map = {
            "/api/admin/categories": ("categories", "category"),
            "/api/admin/tasks": ("tasks", "task"),
            "/api/admin/vehicles": ("vehicles", "vehicle"),
            "/api/admin/users": ("users", "user"),
        }
        if path not in route_map:
            return

        coll_name, key = route_map[path]

        if not isinstance(resp_body, dict):
            self._record_uncertain_cleanup_obligation(coll_name, None)

        entity_obj = resp_body.get(key) if isinstance(resp_body.get(key), dict) else resp_body
        entity_id = entity_obj.get("id") if isinstance(entity_obj, dict) else None

        if type(entity_id) is not int or entity_id <= 0:
            self._record_uncertain_cleanup_obligation(coll_name, None)

        reval_ok = False
        try:
            reval_ok = self.revalidate_resource(coll_name, entity_id, headers=headers)
        except Exception:
            reval_ok = False

        if not reval_ok:
            self._record_uncertain_cleanup_obligation(coll_name, entity_id)

    def guarded_request(
        self,
        method: str,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[Dict[str, Any]] = None,
    ) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
        self.validate_origin()
        self.assert_safe_request(method, path, body=body)

        if method.upper() == "POST":
            collection_post_routes = {
                "/api/admin/categories": "categories",
                "/api/admin/tasks": "tasks",
                "/api/admin/vehicles": "vehicles",
                "/api/admin/users": "users",
            }
            # Uncertain POST policy: never retry on transport failure
            try:
                status, resp_body, resp_headers = self.transport.request(method, path, headers=headers, body=body)
            except Exception as e:
                if path in collection_post_routes:
                    self._record_uncertain_cleanup_obligation(collection_post_routes[path], None)
                # Mark uncertain and raise without retrying
                raise QASecurityError("ERR_POST_CREATION_UNVERIFIED") from None

            if status in (200, 201):
                self._register_created_post_resource(path, body, resp_body, headers=headers)

            return status, resp_body, resp_headers

        status, resp_body, resp_headers = self.transport.request(method, path, headers=headers, body=body)

        # Remove deleted resource from active ownership on success or 404
        if method.upper() == "DELETE" and status in (200, 204, 404):
            id_mutation_match = re.match(r"^/api/admin/(categories|tasks|vehicles|users)/(\d+)$", path)
            if id_mutation_match:
                coll = id_mutation_match.group(1)
                del_id = int(id_mutation_match.group(2))
                self._active_verified_ownership.get(coll, {}).pop(del_id, None)

        return status, resp_body, resp_headers

    def sanitize_error_message(self, message: Any) -> str:
        return sanitize_error_code(message)

    def _validate_journal_dir(self, dir_path: str):
        if not os.path.lexists(dir_path):
            os.makedirs(dir_path, mode=0o700, exist_ok=True)
        st = os.lstat(dir_path)
        if stat.S_ISLNK(st.st_mode):
            raise QASecurityError(f"ERR_JOURNAL_SYMLINK: Journal directory '{dir_path}' must not be a symlink.")
        if not stat.S_ISDIR(st.st_mode):
            raise QASecurityError(f"ERR_JOURNAL_DIR: Journal directory '{dir_path}' is not a directory.")
        if st.st_uid != os.getuid():
            raise QASecurityError(f"ERR_JOURNAL_PERMS: Journal directory '{dir_path}' foreign owner UID {st.st_uid} != {os.getuid()}.")
        if (st.st_mode & 0o777) != 0o700:
            try:
                os.chmod(dir_path, 0o700)
            except OSError:
                pass

    def get_journal_path(self) -> str:
        self._validate_journal_dir(self.journal_dir)
        return os.path.join(self.journal_dir, f"{self.run_id}.json")

    def sync_journal(self):
        """Persists journal to 0600 file outside Git atomically.

        Same-directory exclusive temporary file (0600) + flush/fsync then atomic os.replace.
        Avoids symlink overwrite and temp collisions; failure before commit preserves old bytes.
        """
        if self._domain_mutation_stopped and self.blocked_reason == "ERR_JOURNAL_DURABILITY_UNCERTAIN":
            raise QASecurityError("ERR_JOURNAL_DURABILITY_UNCERTAIN: Journal mutations latched due to uncertain durability.")

        path = self.get_journal_path()
        if os.path.lexists(path):
            st_path = os.lstat(path)
            if stat.S_ISLNK(st_path.st_mode):
                raise QASecurityError(f"ERR_JOURNAL_SYMLINK: Journal path '{path}' must not be a symlink.")
            if not stat.S_ISREG(st_path.st_mode):
                raise QASecurityError(f"ERR_JOURNAL_FILE: Journal path '{path}' must be a regular file.")
            if st_path.st_nlink > 1:
                raise QASecurityError(f"ERR_JOURNAL_HARDLINK: Journal path '{path}' has multiple hardlinks ({st_path.st_nlink}).")
            if st_path.st_uid != os.getuid():
                raise QASecurityError(f"ERR_JOURNAL_PERMS: Journal path '{path}' foreign owner UID {st_path.st_uid} != {os.getuid()}.")

        dir_path = os.path.dirname(path)
        content = json.dumps(self.journal, indent=2)
        tmp_path = f"{path}.tmp.{os.getpid()}.{secrets.token_hex(4)}"
        fd = None
        created_stat = None
        replace_committed = False
        try:
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            fd = os.open(tmp_path, flags, 0o600)
            created_stat = os.fstat(fd)
            with open(fd, "w", encoding="utf-8", closefd=False) as f:
                f.write(content)
                f.flush()
                os.fsync(fd)
            os.close(fd)
            fd = None

            # Safe atomic replace: directory is on same filesystem
            os.replace(tmp_path, path)
            replace_committed = True

            # Directory fsync for crash durability
            try:
                dir_flags = os.O_RDONLY
                if hasattr(os, "O_DIRECTORY"):
                    dir_flags |= os.O_DIRECTORY
                dfd = os.open(dir_path, dir_flags)
                try:
                    os.fsync(dfd)
                finally:
                    os.close(dfd)
            except OSError as e:
                self._trip_domain_mutation_latch("ERR_JOURNAL_DURABILITY_UNCERTAIN")
                raise QASecurityError(f"ERR_JOURNAL_DURABILITY_UNCERTAIN: Directory fsync failed after commit: {mask_secrets(str(e))}") from None
        except Exception:
            if fd is not None:
                try:
                    os.close(fd)
                except OSError:
                    pass
            if not replace_committed and created_stat is not None:
                try:
                    cur_st = os.lstat(tmp_path)
                    if cur_st.st_ino == created_stat.st_ino and cur_st.st_dev == created_stat.st_dev:
                        os.unlink(tmp_path)
                except OSError:
                    pass
            raise

    def journal_record_resource(self, resource_type: str, item_id: int, identifier: str):
        if resource_type == "worker_user":
            self.journal["resources"]["worker_user"] = {"id": item_id, "username": identifier}
        elif resource_type == "category":
            self.journal["resources"]["category"] = {"id": item_id, "name": identifier}
        elif resource_type == "task":
            self.journal["resources"]["tasks"].append({"id": item_id, "name": identifier})
        elif resource_type == "vehicle":
            self.journal["resources"]["vehicle"] = {"id": item_id, "plate": identifier}
        self.sync_journal()

    def get_dry_run_plan(self) -> Dict[str, Any]:
        return {
            "mode": "DRY-RUN (default)",
            "live_calls_permitted": False,
            "origin": self.origin,
            "nonce": self.nonce,
            "journal_path": self.get_journal_path(),
            "prohibited_actions": [
                "No live HTTP requests executed",
                "Drivers POST/PUT/PATCH/DELETE strictly forbidden",
                "TimeSlots PUT/PATCH strictly forbidden",
                "Reports/Loading modification strictly forbidden",
                "Self-service password/profile modification strictly forbidden",
            ],
            "execution_steps": [
                "1. Authenticate admin with credentials from config",
                "2. Create unique worker (role 2) with strong generated password",
                "3. Create QA category and admin task",
                "4. Authenticate worker and create worker task",
                "5. Exercise safe admin edits on task and category",
                "6. Exercise vehicle lifecycle (create, edit, toggle, delete)",
                "7. Validate worker permission matrix (own task edit vs 403 on admin task, toggle, cat write, etc.)",
                "8. Deactivate worker and verify 401 revocation on profile",
                "9. Reverse cleanup (tasks -> category -> vehicle -> worker -> logout)",
            ],
        }

    def load_admin_credentials(self) -> Dict[str, str]:
        """Loads admin credentials in memory only; ensures 0600 file mode."""
        if self._admin_credentials:
            return self._admin_credentials

        if not os.path.exists(self.config_path):
            raise QASecurityError(
                f"Missing auth config at '{self.config_path}'. Required for --execute-production."
            )

        # Validate file permissions (must be mode 0600)
        st = os.stat(self.config_path)
        file_mode = stat.S_IMODE(st.st_mode)
        if file_mode != 0o600:
            raise QASecurityError(
                f"Insecure permissions {oct(file_mode)} on '{self.config_path}'. Must be 0600."
            )

        with open(self.config_path, "r", encoding="utf-8") as f:
            creds = json.load(f)

        if "username" not in creds or "password" not in creds:
            raise QASecurityError(f"Auth config at '{self.config_path}' must contain 'username' and 'password'.")

        self._admin_credentials = creds
        return self._admin_credentials

    # ----------------- Scenario Operations -----------------

    def admin_login(self):
        creds = self.load_admin_credentials()
        status, body, _ = self.guarded_request(
            "POST",
            "/api/auth/login",
            body={"username": creds["username"], "password": creds["password"]},
        )
        if status != 200 or "token" not in body:
            raise Exception(f"Admin login failed: status {status}")
        self.admin_token = body["token"]
        self.admin_refresh_token = body.get("refreshToken")
        self.admin_user_id = body.get("user", {}).get("id")

    def create_unique_worker(self) -> int:
        username = f"qa_worker_{self.nonce}"
        display_name = f"QA Worker {self.nonce}"
        self.worker_password = secrets.token_urlsafe(18) + "!9A"
        headers = {"Authorization": f"Bearer {self.admin_token}"}
        status, body, _ = self.guarded_request(
            "POST",
            "/api/admin/users",
            headers=headers,
            body={
                "username": username,
                "password": self.worker_password,
                "display_name": display_name,
                "role_id": 2,  # Worker role
            },
        )
        if status != 201 or "user" not in body:
            raise Exception(f"Worker creation failed: status {status}")
        self.worker_id = body["user"]["id"]
        self.journal_record_resource("worker_user", self.worker_id, username)
        return self.worker_id

    def create_qa_category(self, admin_token: str) -> int:
        cat_name = f"qa_cat_{self.nonce}"
        headers = {"Authorization": f"Bearer {admin_token}"}
        status, body, _ = self.guarded_request(
            "POST",
            "/api/admin/categories",
            headers=headers,
            body={
                "name_pt": cat_name,
                "name_es": cat_name,
                "category_type": "check",
                "sort_order": 999,
            },
        )
        if status != 201 or "category" not in body:
            raise Exception(f"Category creation failed: status {status}")
        self.category_id = body["category"]["id"]
        self.journal_record_resource("category", self.category_id, cat_name)
        return self.category_id

    def create_qa_task(self, token: str, is_admin: bool = True) -> int:
        prefix = "admin" if is_admin else "worker"
        task_name = f"qa_task_{prefix}_{self.nonce}"
        headers = {"Authorization": f"Bearer {token}"}
        status, body, _ = self.guarded_request(
            "POST",
            "/api/admin/tasks",
            headers=headers,
            body={
                "category_id": self.category_id,
                "name_pt": task_name,
                "name_es": task_name,
                "temperature_readings": 1,
            },
        )
        if status != 201 or "task" not in body:
            raise Exception(f"Task creation ({prefix}) failed: status {status}")
        task_id = body["task"]["id"]
        if is_admin:
            self.admin_task_id = task_id
        else:
            self.worker_task_id = task_id
        self.journal_record_resource("task", task_id, task_name)
        return task_id

    def worker_login(self):
        username = f"qa_worker_{self.nonce}"
        status, body, _ = self.guarded_request(
            "POST",
            "/api/auth/login",
            body={"username": username, "password": self.worker_password},
        )
        if status != 200 or "token" not in body:
            raise Exception(f"Worker login failed: status {status}")
        self.worker_token = body["token"]
        self.worker_refresh_token = body.get("refreshToken")

    def exercise_safe_admin_edits(self):
        headers = {"Authorization": f"Bearer {self.admin_token}"}
        # Safe edit admin task
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/tasks/{self.admin_task_id}",
            headers=headers,
            body={"name_pt": f"qa_task_admin_edit_{self.nonce}"},
        )
        if status != 200:
            raise Exception(f"Admin task edit failed: status {status}")

        # Safe edit QA category
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/categories/{self.category_id}",
            headers=headers,
            body={"name_pt": f"qa_cat_edit_{self.nonce}"},
        )
        if status != 200:
            raise Exception(f"Admin category edit failed: status {status}")

    def exercise_vehicle_fixture_lifecycle(self):
        headers = {"Authorization": f"Bearer {self.admin_token}"}
        plate = f"QA{self.nonce[:5].upper()}"
        desc = f"qa_vehicle_{self.nonce}"

        # 1. Create vehicle
        status, body, _ = self.guarded_request(
            "POST",
            "/api/admin/vehicles",
            headers=headers,
            body={"description": desc, "license_plate": plate},
        )
        if status != 201 or "vehicle" not in body:
            raise Exception(f"Vehicle creation failed: status {status}")
        self.vehicle_id = body["vehicle"]["id"]
        self.journal_record_resource("vehicle", self.vehicle_id, plate)

        # 2. Edit vehicle
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/vehicles/{self.vehicle_id}",
            headers=headers,
            body={"description": f"{desc}_updated"},
        )
        if status != 200:
            raise Exception(f"Vehicle edit failed: status {status}")

        # 3. Toggle vehicle
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/vehicles/{self.vehicle_id}",
            headers=headers,
            body={"is_active": 0},
        )
        if status != 200:
            raise Exception(f"Vehicle toggle inactive failed: status {status}")
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/vehicles/{self.vehicle_id}",
            headers=headers,
            body={"is_active": 1},
        )
        if status != 200:
            raise Exception(f"Vehicle toggle active failed: status {status}")

        # 4. Delete vehicle with GET verification
        self.delete_verified_vehicle(self.vehicle_id, self.admin_token)
        self.vehicle_id = None

    def exercise_worker_authorization_matrix(self) -> bool:
        """Exercises worker matrix: own edits allowed, cross-boundary actions yield 403."""
        worker_headers = {"Authorization": f"Bearer {self.worker_token}"}

        # 1. Worker edits own task -> SUCCESS (200)
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/tasks/{self.worker_task_id}",
            headers=worker_headers,
            body={"name_pt": f"qa_task_worker_edit_{self.nonce}"},
        )
        if status != 200:
            raise Exception(f"Worker edit of own task expected 200, got {status}")

        # 2. Worker edits admin task -> EXPECTED 403
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/tasks/{self.admin_task_id}",
            headers=worker_headers,
            body={"name_pt": f"qa_task_admin_edit_{self.nonce}"},
        )
        if status != 403:
            raise Exception(f"Worker edit of admin task expected 403, got {status}")

        # 3. Worker task toggle (is_active modification) -> EXPECTED 403
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/tasks/{self.worker_task_id}",
            headers=worker_headers,
            body={"is_active": 0},
        )
        if status != 403:
            raise Exception(f"Worker task toggle expected 403, got {status}")

        # 4. Worker delete admin task -> EXPECTED 403
        status, _, _ = self.guarded_request(
            "DELETE",
            f"/api/admin/tasks/{self.admin_task_id}",
            headers=worker_headers,
        )
        if status != 403:
            raise Exception(f"Worker delete of admin task expected 403, got {status}")

        # 5. Worker category write probe -> EXPECTED 403
        # Finding 4: Worker forbidden-write probe uses harmless PATCH of owned QA category
        # instead of untagged POST /api/admin/categories
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/categories/{self.category_id}",
            headers=worker_headers,
            body={"name_pt": f"qa_cat_{self.nonce}"},
        )
        if status != 403:
            raise Exception(f"Worker categories write probe expected 403, got {status}")

        # 6. Worker vehicles read -> EXPECTED 403
        status, _, _ = self.guarded_request(
            "GET",
            "/api/admin/vehicles",
            headers=worker_headers,
        )
        if status != 403:
            raise Exception(f"Worker vehicles read expected 403, got {status}")

        # 7. Worker users read -> EXPECTED 403
        status, _, _ = self.guarded_request(
            "GET",
            "/api/admin/users",
            headers=worker_headers,
        )
        if status != 403:
            raise Exception(f"Worker users read expected 403, got {status}")

        return True

    def exercise_worker_deactivation_revocation(self) -> bool:
        """Deactivates worker and verifies profile endpoint answers 401."""
        admin_headers = {"Authorization": f"Bearer {self.admin_token}"}
        status, _, _ = self.guarded_request(
            "PATCH",
            f"/api/admin/users/{self.worker_id}",
            headers=admin_headers,
            body={"is_active": 0},
        )
        if status != 200:
            raise Exception(f"Worker deactivation expected 200, got {status}")

        worker_headers = {"Authorization": f"Bearer {self.worker_token}"}
        status, _, _ = self.guarded_request(
            "GET",
            "/api/auth/profile",
            headers=worker_headers,
        )
        if status != 401:
            raise Exception(f"Revoked worker profile read expected 401, got {status}")

        return True

    # ----------------- Deletion & Verification -----------------

    def delete_verified_task(self, task_id: int, token: str):
        if type(task_id) is not int or task_id <= 0:
            raise QASecurityError("ERR_INVALID_ID: task_id must be a positive integer")

        tracked_cat_ids = self._get_tracked_ids("categories")
        if not tracked_cat_ids or (self.category_id is not None and self.category_id not in tracked_cat_ids):
            raise QASecurityError("ERR_TASK_NO_ACTIVE_CATEGORY: task deletion requires an active verified QA category")

        active_cat_id = self.category_id if self.category_id is not None else next(iter(tracked_cat_ids))

        headers = {"Authorization": f"Bearer {token}"}
        tasks = self._get_and_validate_tasks(headers)
        matched = next((t for t in tasks if isinstance(t, dict) and type(t.get("id")) is int and t.get("id") == task_id and t.get("id") > 0), None)
        if not matched:
            return  # Already deleted

        task_name = matched.get("name_pt")
        expected_task_names = {
            f"qa_task_admin_{self.nonce}",
            f"qa_task_worker_{self.nonce}",
            f"qa_task_admin_edit_{self.nonce}",
            f"qa_task_worker_edit_{self.nonce}",
        }
        if not isinstance(task_name, str) or task_name not in expected_task_names:
            raise QASecurityError("ERR_TASK_OWNERSHIP: task does not match expected QA fixture identity")

        if "category_id" not in matched:
            raise QASecurityError("ERR_TASK_CATEGORY_MISMATCH: missing category_id")
        t_cat_id = matched["category_id"]
        if type(t_cat_id) is not int or t_cat_id != active_cat_id:
            raise QASecurityError("ERR_TASK_CATEGORY_MISMATCH: task category does not match QA fixture")

        was_already_owned = task_id in self._active_verified_ownership.get("tasks", {})
        self._active_verified_ownership["tasks"][task_id] = {"name_pt": task_name, "category_id": active_cat_id}
        self.journal_record_resource("task", task_id, task_name)
        try:
            del_status, _, _ = self.guarded_request("DELETE", f"/api/admin/tasks/{task_id}", headers=headers)
            if del_status not in (200, 404):
                raise QASecurityError("ERR_DELETE_FAILED: DELETE task failed with unexpected status")
        except Exception:
            if not was_already_owned:
                self._active_verified_ownership["tasks"].pop(task_id, None)
            raise

    def _validate_category_list(self, body: Any) -> List[Dict[str, Any]]:
        """Validates that categories GET body is well-formed with unique positive integer IDs."""
        if not isinstance(body, dict) or "categories" not in body:
            raise QASecurityError("ERR_CATEGORIES_MALFORMED: missing 'categories' object")
        categories = body["categories"]
        if not isinstance(categories, list):
            raise QASecurityError("ERR_CATEGORIES_MALFORMED: 'categories' is not a list")

        seen_ids = set()
        for cat in categories:
            if not isinstance(cat, dict):
                raise QASecurityError("ERR_CATEGORIES_MALFORMED: category entry is not a JSON object")
            cid = cat.get("id")
            if not isinstance(cid, int) or isinstance(cid, bool) or cid <= 0:
                raise QASecurityError("ERR_CATEGORIES_MALFORMED: invalid category id")
            if cid in seen_ids:
                raise QASecurityError("ERR_CATEGORIES_MALFORMED: duplicate category id")
            seen_ids.add(cid)

            # parent_category_id key must be explicitly present per wire contract (null for root)
            if "parent_category_id" not in cat:
                raise QASecurityError("ERR_CATEGORIES_MALFORMED: missing parent_category_id key")
            pid = cat["parent_category_id"]
            if pid is not None and (not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0):
                raise QASecurityError("ERR_CATEGORIES_MALFORMED: invalid parent_category_id")

        return categories

    def _get_and_validate_tasks(self, headers: Dict[str, str]) -> List[Dict[str, Any]]:
        """Queries and validates the tasks list. Fails closed on any non-200 or malformed entry."""
        status, body, _ = self.guarded_request("GET", "/api/admin/tasks", headers=headers)
        if status != 200:
            raise QASecurityError("ERR_TASKS_STATUS: tasks listing failed with non-200 status")
        if not isinstance(body, dict) or "tasks" not in body:
            raise QASecurityError("ERR_TASKS_MALFORMED: missing 'tasks' object")
        tasks = body["tasks"]
        if not isinstance(tasks, list):
            raise QASecurityError("ERR_TASKS_MALFORMED: 'tasks' is not a list")

        seen_ids = set()
        for t in tasks:
            if not isinstance(t, dict):
                raise QASecurityError("ERR_TASKS_MALFORMED: task entry is not a JSON object")
            tid = t.get("id")
            if not isinstance(tid, int) or isinstance(tid, bool) or tid <= 0:
                raise QASecurityError("ERR_TASKS_MALFORMED: invalid task id")
            if tid in seen_ids:
                raise QASecurityError("ERR_TASKS_MALFORMED: duplicate task id")
            seen_ids.add(tid)

            # category_id key must be present and must be a positive integer per backend schema
            if "category_id" not in t:
                raise QASecurityError("ERR_TASKS_MALFORMED: missing category_id key")
            t_cat_id = t["category_id"]
            if not isinstance(t_cat_id, int) or isinstance(t_cat_id, bool) or t_cat_id <= 0:
                raise QASecurityError("ERR_TASKS_MALFORMED: invalid category_id in task")

        return tasks

    def delete_verified_category(self, category_id: int, token: str):
        if type(category_id) is not int or category_id <= 0:
            raise QASecurityError("ERR_INVALID_ID: category_id must be a positive integer")

        if self.category_id is not None and category_id != self.category_id:
            raise QASecurityError("ERR_CATEGORY_MISMATCH: category does not match recorded QA fixture")

        headers = {"Authorization": f"Bearer {token}"}
        expected_cat_names = {f"qa_cat_{self.nonce}", f"qa_cat_edit_{self.nonce}"}
        expected_task_names = {
            f"qa_task_admin_{self.nonce}",
            f"qa_task_worker_{self.nonce}",
            f"qa_task_admin_edit_{self.nonce}",
            f"qa_task_worker_edit_{self.nonce}",
        }

        # 1. Require categories GET 200, well-formed list, unique numeric IDs
        status, body, _ = self.guarded_request("GET", "/api/admin/categories", headers=headers)
        if status != 200:
            raise QASecurityError("ERR_CATEGORIES_STATUS: categories listing failed with non-200 status")

        categories = self._validate_category_list(body)
        matched = next((c for c in categories if isinstance(c, dict) and type(c.get("id")) is int and c.get("id") == category_id and c.get("id") > 0), None)
        if not matched:
            return  # Already deleted

        cat_name = matched.get("name_pt")
        if not isinstance(cat_name, str) or cat_name not in expected_cat_names:
            raise QASecurityError("ERR_CATEGORY_OWNERSHIP: category does not match expected QA fixture identity")

        # Category parent field check: foreign descendants or unexpected child references abort
        child_categories = [c for c in categories if c.get("parent_category_id") == category_id]
        if child_categories:
            raise QASecurityError("ERR_CATEGORY_CHILD_REFERENCE: category has child category references")

        # 2. Query and validate tasks BEFORE deleting ANY task
        tasks = self._get_and_validate_tasks(headers)
        remaining_tasks = [t for t in tasks if t["category_id"] == category_id]

        # Validate ALL children/foreign tasks BEFORE deleting ANY owned task
        foreign_tasks = [
            t for t in remaining_tasks
            if not isinstance(t.get("name_pt"), str) or t["name_pt"] not in expected_task_names
        ]
        if foreign_tasks:
            raise QASecurityError("ERR_TASK_FOREIGN: category contains foreign or ambiguous tasks")

        # Validation has succeeded! NOW and ONLY NOW register category into active verified ownership
        was_already_owned = category_id in self._active_verified_ownership.get("categories", {})
        prior_category_id = self.category_id

        if self.category_id is None:
            self.category_id = category_id
        self._active_verified_ownership["categories"][category_id] = {"name_pt": cat_name}
        self.journal_record_resource("category", category_id, cat_name)

        try:
            # Delete verified owned tasks
            for t in remaining_tasks:
                self.delete_verified_task(t["id"], token)

            # 3. Final refresh: ALWAYS re-query tasks and categories (even if initially empty)
            # Note: Non-atomic REST cannot eliminate TOCTOU window between fresh GET and DELETE;
            # fail-closed double-check minimizes window as best possible given API constraints.
            fresh_tasks = self._get_and_validate_tasks(headers)
            unresolved_tasks = [t for t in fresh_tasks if t["category_id"] == category_id]
            if unresolved_tasks:
                raise QASecurityError("ERR_CATEGORY_HAS_REMAINING_TASKS: category has remaining tasks before deletion")

            c_status, c_body, _ = self.guarded_request("GET", "/api/admin/categories", headers=headers)
            if c_status != 200:
                raise QASecurityError("ERR_CATEGORIES_STATUS: categories re-listing failed with non-200 status")
            fresh_categories = self._validate_category_list(c_body)
            fresh_matched = next((c for c in fresh_categories if c["id"] == category_id), None)
            if not fresh_matched:
                return  # Already deleted
            fresh_cat_name = fresh_matched.get("name_pt")
            if not isinstance(fresh_cat_name, str) or fresh_cat_name not in expected_cat_names:
                raise QASecurityError("ERR_CATEGORY_OWNERSHIP: category does not match expected QA fixture identity")

            fresh_child_categories = [c for c in fresh_categories if c.get("parent_category_id") == category_id]
            if fresh_child_categories:
                raise QASecurityError("ERR_CATEGORY_CHILD_REFERENCE: category has child category references on final refresh")

            # 4. Final category delete
            del_status, _, _ = self.guarded_request("DELETE", f"/api/admin/categories/{category_id}", headers=headers)
            if del_status not in (200, 404):
                raise QASecurityError("ERR_DELETE_FAILED: DELETE category failed with unexpected status")
        except Exception:
            if not was_already_owned:
                self._active_verified_ownership["categories"].pop(category_id, None)
                self.category_id = prior_category_id
            raise

    def delete_verified_vehicle(self, vehicle_id: int, token: str):
        if type(vehicle_id) is not int or vehicle_id <= 0:
            raise QASecurityError(f"Safety check failed: invalid vehicle_id {vehicle_id}.")
        headers = {"Authorization": f"Bearer {token}"}
        status, body, _ = self.guarded_request("GET", "/api/admin/vehicles", headers=headers)
        if status != 200:
            raise Exception(f"Failed to list vehicles for verification: status {status}")

        vehicles = body.get("vehicles", [])
        matched = next((v for v in vehicles if isinstance(v, dict) and type(v.get("id")) is int and v.get("id") == vehicle_id and v.get("id") > 0), None)
        if not matched:
            return

        desc = matched.get("description", "")
        plate = matched.get("license_plate", "")
        expected_desc = {f"qa_vehicle_{self.nonce}", f"qa_vehicle_{self.nonce}_updated"}
        expected_plate = f"QA{self.nonce[:5].upper()}"
        if desc not in expected_desc or plate != expected_plate:
            raise QASecurityError(
                f"Safety check failed: vehicle {vehicle_id} does not belong to run nonce '{self.nonce}'."
            )

        was_already_owned = vehicle_id in self._active_verified_ownership.get("vehicles", {})
        prior_vehicle_id = self.vehicle_id

        if self.vehicle_id is None:
            self.vehicle_id = vehicle_id
        self._active_verified_ownership["vehicles"][vehicle_id] = {"description": desc, "license_plate": plate}
        self.journal_record_resource("vehicle", vehicle_id, plate)

        try:
            del_status, _, _ = self.guarded_request("DELETE", f"/api/admin/vehicles/{vehicle_id}", headers=headers)
            if del_status not in (200, 404):
                raise Exception(f"Failed to delete vehicle {vehicle_id}: status {del_status}")
        except Exception:
            if not was_already_owned:
                self._active_verified_ownership["vehicles"].pop(vehicle_id, None)
                self.vehicle_id = prior_vehicle_id
            raise

    def delete_verified_worker(self, worker_id: int, token: str):
        if type(worker_id) is not int or worker_id <= 0:
            raise QASecurityError(f"Safety check failed: invalid worker_id {worker_id}.")
        if worker_id == 1 or (self.admin_user_id is not None and worker_id == self.admin_user_id):
            raise QASecurityError(f"Safety check failed: user {worker_id} is a reserved admin user and cannot be deleted.")

        headers = {"Authorization": f"Bearer {token}"}
        status, body, _ = self.guarded_request("GET", "/api/admin/users", headers=headers)
        if status != 200:
            raise Exception(f"Failed to list users for verification: status {status}")

        users = body.get("users", [])
        matched = next((u for u in users if isinstance(u, dict) and type(u.get("id")) is int and u.get("id") == worker_id and u.get("id") > 0), None)
        if not matched:
            return

        username = matched.get("username", "")
        if "role_id" not in matched:
            raise QASecurityError(
                f"Safety check failed: user {worker_id} missing role_id."
            )
        role_id = matched["role_id"]
        expected_username = f"qa_worker_{self.nonce}"
        if username != expected_username or type(role_id) is not int or role_id != 2:
            raise QASecurityError(
                f"Safety check failed: user {worker_id} ('{username}') does not belong to run nonce '{self.nonce}' or role is not 2."
            )

        was_already_owned = worker_id in self._active_verified_ownership.get("users", {})
        prior_worker_id = self.worker_id

        if self.worker_id is None:
            self.worker_id = worker_id
        self._active_verified_ownership["users"][worker_id] = {"username": username, "role_id": 2}
        self.journal_record_resource("worker_user", worker_id, username)

        try:
            del_status, _, _ = self.guarded_request("DELETE", f"/api/admin/users/{worker_id}", headers=headers)
            if del_status not in (200, 404):
                raise Exception(f"Failed to delete worker {worker_id}: status {del_status}")
        except Exception:
            if not was_already_owned:
                self._active_verified_ownership["users"].pop(worker_id, None)
                self.worker_id = prior_worker_id
            raise

    # ----------------- Reverse Cleanup -----------------

    def detect_own_nonce_resources(self):
        """Queries GET endpoints to discover any own-nonce resources created by uncertain POSTs."""
        admin_token = self.admin_token
        if not admin_token:
            return
        headers = {"Authorization": f"Bearer {admin_token}"}

        # 1. Detect orphaned category
        try:
            status, body, _ = self.guarded_request("GET", "/api/admin/categories", headers=headers)
            if status != 200:
                status_code = status if isinstance(status, int) and 100 <= status <= 599 else 500
                err_code = f"ERR_DISCOVERY_STATUS_{status_code}"
                self.journal["residuals"].append({"type": "category", "error": err_code})
                obligation = {"entity_type": "categories", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            elif (
                not isinstance(body, dict)
                or "categories" not in body
                or not isinstance(body["categories"], list)
                or not all(self._is_valid_collection_entry("categories", item) for item in body["categories"])
            ):
                err_code = "ERR_DISCOVERY_SCHEMA"
                self.journal["residuals"].append({"type": "category", "error": err_code})
                obligation = {"entity_type": "categories", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            else:
                for cat in body["categories"]:
                    c_name = cat.get("name_pt", "")
                    if self.is_owned_label("category", c_name) and cat.get("parent_category_id") is None:
                        cid = cat.get("id")
                        if type(cid) is int and cid > 0 and not self.category_id:
                            self.category_id = cid
                            self._active_verified_ownership["categories"][cid] = {"name_pt": c_name}
                            self.journal_record_resource("category", cid, c_name)
        except Exception as e:
            if isinstance(e, QASecurityError) and ("ERR_DISCOVERY_STATUS" in str(e) or "ERR_DISCOVERY_SCHEMA" in str(e)):
                raise
            err_code = self.sanitize_error_message(str(e))
            self.journal["residuals"].append({"type": "category", "error": err_code})
            obligation = {"entity_type": "categories", "run_ref": self.nonce}
            if obligation not in self.pending_cleanup_obligations:
                self.pending_cleanup_obligations.append(obligation)
            if "pending_cleanup_obligations" not in self.journal:
                self.journal["pending_cleanup_obligations"] = []
            if obligation not in self.journal["pending_cleanup_obligations"]:
                self.journal["pending_cleanup_obligations"].append(obligation)
            self.sync_journal()
            raise QASecurityError(err_code) from None

        # 2. Detect orphaned tasks
        try:
            status, body, _ = self.guarded_request("GET", "/api/admin/tasks", headers=headers)
            if status != 200:
                status_code = status if isinstance(status, int) and 100 <= status <= 599 else 500
                err_code = f"ERR_DISCOVERY_STATUS_{status_code}"
                self.journal["residuals"].append({"type": "task", "error": err_code})
                obligation = {"entity_type": "tasks", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            elif (
                not isinstance(body, dict)
                or "tasks" not in body
                or not isinstance(body["tasks"], list)
                or not all(self._is_valid_collection_entry("tasks", item) for item in body["tasks"])
            ):
                err_code = "ERR_DISCOVERY_SCHEMA"
                self.journal["residuals"].append({"type": "task", "error": err_code})
                obligation = {"entity_type": "tasks", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            else:
                for task in body["tasks"]:
                    t_name = task.get("name_pt", "")
                    t_cat = task.get("category_id")
                    tid = task.get("id")
                    if (
                        type(tid) is int and tid > 0
                        and type(t_cat) is int and t_cat > 0
                        and self.is_owned_label("task", t_name)
                        and (self.category_id is None or t_cat == self.category_id)
                    ):
                        if tid not in [t["id"] for t in self.journal["resources"]["tasks"]]:
                            self._active_verified_ownership["tasks"][tid] = {"name_pt": t_name, "category_id": t_cat}
                            self.journal_record_resource("task", tid, t_name)
                            if not self.admin_task_id:
                                self.admin_task_id = tid
        except Exception as e:
            if isinstance(e, QASecurityError) and ("ERR_DISCOVERY_STATUS" in str(e) or "ERR_DISCOVERY_SCHEMA" in str(e)):
                raise
            err_code = self.sanitize_error_message(str(e))
            self.journal["residuals"].append({"type": "task", "error": err_code})
            obligation = {"entity_type": "tasks", "run_ref": self.nonce}
            if obligation not in self.pending_cleanup_obligations:
                self.pending_cleanup_obligations.append(obligation)
            if "pending_cleanup_obligations" not in self.journal:
                self.journal["pending_cleanup_obligations"] = []
            if obligation not in self.journal["pending_cleanup_obligations"]:
                self.journal["pending_cleanup_obligations"].append(obligation)
            self.sync_journal()
            raise QASecurityError(err_code) from None

        # 3. Detect orphaned vehicle
        try:
            status, body, _ = self.guarded_request("GET", "/api/admin/vehicles", headers=headers)
            if status != 200:
                status_code = status if isinstance(status, int) and 100 <= status <= 599 else 500
                err_code = f"ERR_DISCOVERY_STATUS_{status_code}"
                self.journal["residuals"].append({"type": "vehicle", "error": err_code})
                obligation = {"entity_type": "vehicles", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            elif (
                not isinstance(body, dict)
                or "vehicles" not in body
                or not isinstance(body["vehicles"], list)
                or not all(self._is_valid_collection_entry("vehicles", item) for item in body["vehicles"])
            ):
                err_code = "ERR_DISCOVERY_SCHEMA"
                self.journal["residuals"].append({"type": "vehicle", "error": err_code})
                obligation = {"entity_type": "vehicles", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            else:
                for veh in body["vehicles"]:
                    desc = veh.get("description", "")
                    plate = veh.get("license_plate", "")
                    vid = veh.get("id")
                    if type(vid) is int and vid > 0 and self.is_owned_label("vehicle", desc) and plate == f"QA{self.nonce[:5].upper()}":
                        if not self.vehicle_id:
                            self.vehicle_id = vid
                            self._active_verified_ownership["vehicles"][vid] = {"description": desc, "license_plate": plate}
                            self.journal_record_resource("vehicle", vid, plate)
        except Exception as e:
            if isinstance(e, QASecurityError) and ("ERR_DISCOVERY_STATUS" in str(e) or "ERR_DISCOVERY_SCHEMA" in str(e)):
                raise
            err_code = self.sanitize_error_message(str(e))
            self.journal["residuals"].append({"type": "vehicle", "error": err_code})
            obligation = {"entity_type": "vehicles", "run_ref": self.nonce}
            if obligation not in self.pending_cleanup_obligations:
                self.pending_cleanup_obligations.append(obligation)
            if "pending_cleanup_obligations" not in self.journal:
                self.journal["pending_cleanup_obligations"] = []
            if obligation not in self.journal["pending_cleanup_obligations"]:
                self.journal["pending_cleanup_obligations"].append(obligation)
            self.sync_journal()
            raise QASecurityError(err_code) from None

        # 4. Detect orphaned worker
        try:
            status, body, _ = self.guarded_request("GET", "/api/admin/users", headers=headers)
            if status != 200:
                status_code = status if isinstance(status, int) and 100 <= status <= 599 else 500
                err_code = f"ERR_DISCOVERY_STATUS_{status_code}"
                self.journal["residuals"].append({"type": "worker_user", "error": err_code})
                obligation = {"entity_type": "users", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            elif (
                not isinstance(body, dict)
                or "users" not in body
                or not isinstance(body["users"], list)
                or not all(self._is_valid_collection_entry("users", item) for item in body["users"])
            ):
                err_code = "ERR_DISCOVERY_SCHEMA"
                self.journal["residuals"].append({"type": "worker_user", "error": err_code})
                obligation = {"entity_type": "users", "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)
                self.sync_journal()
                raise QASecurityError(err_code)
            else:
                for user in body["users"]:
                    u_name = user.get("username", "")
                    u_role = user.get("role_id")
                    uid = user.get("id")
                    if (
                        type(uid) is int and uid > 1
                        and (self.admin_user_id is None or uid != self.admin_user_id)
                        and self.is_owned_label("worker_user", u_name)
                        and type(u_role) is int and u_role == 2
                    ):
                        if not self.worker_id:
                            self.worker_id = uid
                            self._active_verified_ownership["users"][uid] = {"username": u_name, "role_id": 2}
                            self.journal_record_resource("worker_user", uid, u_name)
        except Exception as e:
            if isinstance(e, QASecurityError) and ("ERR_DISCOVERY_STATUS" in str(e) or "ERR_DISCOVERY_SCHEMA" in str(e)):
                raise
            err_code = self.sanitize_error_message(str(e))
            self.journal["residuals"].append({"type": "worker_user", "error": err_code})
            obligation = {"entity_type": "users", "run_ref": self.nonce}
            if obligation not in self.pending_cleanup_obligations:
                self.pending_cleanup_obligations.append(obligation)
            if "pending_cleanup_obligations" not in self.journal:
                self.journal["pending_cleanup_obligations"] = []
            if obligation not in self.journal["pending_cleanup_obligations"]:
                self.journal["pending_cleanup_obligations"].append(obligation)
            self.sync_journal()
            raise QASecurityError(err_code) from None

    def execute_reverse_cleanup(self):
        """Executes strict reverse cleanup: tasks -> category -> vehicle -> worker -> logout.

        Runs both on successful scenario completion and on mid-run exceptions.
        """
        admin_token = self.admin_token
        if not admin_token:
            return

        headers = {"Authorization": f"Bearer {admin_token}"}

        # Detect any resources created by uncertain POSTs before proceeding
        self.detect_own_nonce_resources()

        # 1. Reverse cleanup: Tasks
        tasks_to_clean = []
        for t in self.journal["resources"]["tasks"]:
            if t["id"] not in tasks_to_clean:
                tasks_to_clean.append(t["id"])
        if self.worker_task_id and self.worker_task_id not in tasks_to_clean:
            tasks_to_clean.append(self.worker_task_id)
        if self.admin_task_id and self.admin_task_id not in tasks_to_clean:
            tasks_to_clean.append(self.admin_task_id)

        tasks_cleaned = True
        for tid in tasks_to_clean:
            try:
                self.delete_verified_task(tid, admin_token)
            except Exception as e:
                tasks_cleaned = False
                self.journal["residuals"].append({"type": "task", "id": tid, "error": self.sanitize_error_message(str(e))})

        if tasks_to_clean and tasks_cleaned:
            if "tasks" not in self.journal["cleaned_up"]:
                self.journal["cleaned_up"].append("tasks")

        # 2. Reverse cleanup: Category
        if self.category_id:
            try:
                self.delete_verified_category(self.category_id, admin_token)
                if "category" not in self.journal["cleaned_up"]:
                    self.journal["cleaned_up"].append("category")
            except Exception as e:
                self.journal["residuals"].append({"type": "category", "id": self.category_id, "error": self.sanitize_error_message(str(e))})

        # 3. Reverse cleanup: Vehicle (if still uncleaned)
        if self.vehicle_id:
            try:
                self.delete_verified_vehicle(self.vehicle_id, admin_token)
                if "vehicle" not in self.journal["cleaned_up"]:
                    self.journal["cleaned_up"].append("vehicle")
            except Exception as e:
                self.journal["residuals"].append({"type": "vehicle", "id": self.vehicle_id, "error": self.sanitize_error_message(str(e))})

        # 4. Reverse cleanup: Worker
        if self.worker_id:
            try:
                self.delete_verified_worker(self.worker_id, admin_token)
                if "worker_user" not in self.journal["cleaned_up"]:
                    self.journal["cleaned_up"].append("worker_user")
            except Exception as e:
                self.journal["residuals"].append({"type": "worker_user", "id": self.worker_id, "error": self.sanitize_error_message(str(e))})

        # 5. Logout both sessions
        if self.worker_token:
            try:
                self.guarded_request(
                    "POST",
                    "/api/auth/logout",
                    headers={"Authorization": f"Bearer {self.worker_token}"},
                    body={"refreshToken": self.worker_refresh_token} if self.worker_refresh_token else {},
                )
            except Exception:
                pass  # Token may have been invalidated by deactivation; ignore

        if self.admin_token:
            try:
                status, _, _ = self.guarded_request(
                    "POST",
                    "/api/auth/logout",
                    headers={"Authorization": f"Bearer {self.admin_token}"},
                    body={"refreshToken": self.admin_refresh_token} if self.admin_refresh_token else {},
                )
                if status != 200:
                    self.journal["residuals"].append({"type": "admin_session", "error": f"ERR_LOGOUT_STATUS_{status}"})
            except Exception as e:
                self.journal["residuals"].append({"type": "admin_session", "error": self.sanitize_error_message(str(e))})

        # 6. Post-cleanup confirmation: confirm absence of own entities
        post_checks = [
            ("category", "/api/admin/categories", "categories"),
            ("task", "/api/admin/tasks", "tasks"),
            ("vehicle", "/api/admin/vehicles", "vehicles"),
            ("worker_user", "/api/admin/users", "users"),
        ]
        for ent_type, check_path, coll in post_checks:
            try:
                p_status, p_body, _ = self.guarded_request("GET", check_path, headers=headers)
                if p_status != 200:
                    status_code = p_status if isinstance(p_status, int) and 100 <= p_status <= 599 else 500
                    self.journal["residuals"].append({
                        "type": ent_type,
                        "error": f"ERR_POST_CLEANUP_CHECK_STATUS_{status_code}",
                    })
                    obligation = {"entity_type": coll, "run_ref": self.nonce}
                    if obligation not in self.pending_cleanup_obligations:
                        self.pending_cleanup_obligations.append(obligation)
                    if "pending_cleanup_obligations" not in self.journal:
                        self.journal["pending_cleanup_obligations"] = []
                    if obligation not in self.journal["pending_cleanup_obligations"]:
                        self.journal["pending_cleanup_obligations"].append(obligation)
                    continue

                if (
                    not isinstance(p_body, dict)
                    or coll not in p_body
                    or not isinstance(p_body[coll], list)
                    or not all(self._is_valid_collection_entry(coll, item) for item in p_body[coll])
                ):
                    self.journal["residuals"].append({
                        "type": ent_type,
                        "error": "ERR_POST_CLEANUP_SCHEMA",
                    })
                    obligation = {"entity_type": coll, "run_ref": self.nonce}
                    if obligation not in self.pending_cleanup_obligations:
                        self.pending_cleanup_obligations.append(obligation)
                    if "pending_cleanup_obligations" not in self.journal:
                        self.journal["pending_cleanup_obligations"] = []
                    if obligation not in self.journal["pending_cleanup_obligations"]:
                        self.journal["pending_cleanup_obligations"].append(obligation)
                    continue

                items = p_body[coll]
                for item in items:
                    item_id = item.get("id")
                    if ent_type == "category":
                        name = item.get("name_pt", "")
                        if self.is_owned_label("category", name):
                            self.journal["residuals"].append({
                                "type": "category",
                                "id": item_id,
                                "error": "ERR_RESIDUAL_DETECTED_POST_CLEANUP",
                            })
                    elif ent_type == "task":
                        name = item.get("name_pt", "")
                        if self.is_owned_label("task", name):
                            self.journal["residuals"].append({
                                "type": "task",
                                "id": item_id,
                                "error": "ERR_RESIDUAL_DETECTED_POST_CLEANUP",
                            })
                    elif ent_type == "vehicle":
                        desc = item.get("description", "")
                        plate = item.get("license_plate", "")
                        if self.is_owned_label("vehicle", desc) and plate == f"QA{self.nonce[:5].upper()}":
                            self.journal["residuals"].append({
                                "type": "vehicle",
                                "id": item_id,
                                "error": "ERR_RESIDUAL_DETECTED_POST_CLEANUP",
                            })
                    elif ent_type == "worker_user":
                        username = item.get("username", "")
                        if self.is_owned_label("worker_user", username):
                            self.journal["residuals"].append({
                                "type": "worker_user",
                                "id": item_id,
                                "error": "ERR_RESIDUAL_DETECTED_POST_CLEANUP",
                            })
            except Exception as e:
                err_code = self.sanitize_error_message(str(e))
                self.journal["residuals"].append({
                    "type": ent_type,
                    "error": err_code,
                })
                obligation = {"entity_type": coll, "run_ref": self.nonce}
                if obligation not in self.pending_cleanup_obligations:
                    self.pending_cleanup_obligations.append(obligation)
                if "pending_cleanup_obligations" not in self.journal:
                    self.journal["pending_cleanup_obligations"] = []
                if obligation not in self.journal["pending_cleanup_obligations"]:
                    self.journal["pending_cleanup_obligations"].append(obligation)

        self.sync_journal()

        if self.journal["residuals"]:
            raise QASecurityError(f"ERR_CLEANUP_RESIDUALS_EXIST: {len(self.journal['residuals'])} residuals detected")

    # ----------------- Main Execution -----------------

    def run(self) -> Dict[str, Any]:
        if self.is_dry_run:
            plan = self.get_dry_run_plan()
            self.sync_journal()
            return {"status": "dry_run_completed", "plan": plan}

        # Live production execution
        self.journal["status"] = "running"
        self.sync_journal()

        worker_matrix_ok = False
        revocation_ok = False

        try:
            # 1. Admin login
            self.admin_login()

            # 2. Admin creates unique worker
            self.create_unique_worker()

            # 3. Admin creates QA category
            self.create_qa_category(self.admin_token)

            # 4. Admin creates QA task
            self.create_qa_task(self.admin_token, is_admin=True)

            # 5. Worker login
            self.worker_login()

            # 6. Worker creates worker-owned task
            self.create_qa_task(self.worker_token, is_admin=False)

            # 7. Safe admin edits
            self.exercise_safe_admin_edits()

            # 8. Vehicle fixture lifecycle
            self.exercise_vehicle_fixture_lifecycle()

            # 9. Worker authorization boundaries (403 expected)
            worker_matrix_ok = self.exercise_worker_authorization_matrix()

            # 10. Worker deactivation & 401 profile revocation
            revocation_ok = self.exercise_worker_deactivation_revocation()

            self.journal["status"] = "completed"
            return {
                "status": "success",
                "worker_matrix_verified_403": worker_matrix_ok,
                "worker_revocation_verified_401": revocation_ok,
                "residuals": self.journal["residuals"],
            }
        except Exception as e:
            self.journal["status"] = "failed"
            raise
        finally:
            # Reverse cleanup runs on BOTH success and mid-run error
            self.execute_reverse_cleanup()


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Narrow, isolated production QA runner for Trindade."
    )
    parser.add_argument(
        "--execute-production",
        action="store_true",
        default=False,
        help="Explicitly authorizes live execution against production. Defaults to dry-run.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Run in dry-run mode without issuing network calls (default behavior).",
    )
    parser.add_argument(
        "--config",
        type=str,
        default=DEFAULT_AUTH_CONFIG,
        help=f"Path to auth json file (mode 0600 required). Default: {DEFAULT_AUTH_CONFIG}",
    )
    parser.add_argument(
        "--journal-dir",
        type=str,
        default=DEFAULT_JOURNAL_DIR,
        help=f"Path to journal directory. Default: {DEFAULT_JOURNAL_DIR}",
    )
    parser.add_argument(
        "--origin",
        type=str,
        default=ALLOWED_ORIGIN,
        help=f"Target origin URL. Must be strictly '{ALLOWED_ORIGIN}'.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    try:
        args = parse_args(argv)
        execute_live = args.execute_production and not args.dry_run

        runner = ProductionQARunner(
            origin=args.origin,
            execute_production=execute_live,
            config_path=args.config,
            journal_dir=args.journal_dir,
        )

        result = runner.run()
        if runner.is_dry_run:
            print("=== Trindade Production Admin QA: DRY-RUN PLAN ===")
            print(json.dumps(result["plan"], indent=2))
            print("\nDRY-RUN completed safely: no network calls were made.")
            print("To execute against production, supply: --execute-production")
        else:
            print("=== Trindade Production Admin QA: LIVE EXECUTION COMPLETE ===")
            print(f"Status: {result.get('status')}")
            print(f"Worker 403 matrix verified: {result.get('worker_matrix_verified_403')}")
            print(f"Worker 401 revocation verified: {result.get('worker_revocation_verified_401')}")
            print(f"Cleanup residuals: {len(result.get('residuals', []))}")
        return 0
    except Exception as e:
        sanitized = sanitize_error_code(e)
        sys.stderr.write(f"Error during Production QA: {sanitized}\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
