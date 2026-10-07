# Guard health integration and probe evaluation

## Authorization
User authorizes lossless integration of approved commit 5a69a927c4335441939ebd76dc10bf6b200a8d1b into feature/principal-review-chain, ledger update, and next guard unit. No push, main changes, destructive reset, worktree removal, or emulator operations. Every source unit must remain <=400 changed lines with tests before implementation and native approval/ACK before local commit.

## Tasks
1. [x] Closed: preserve prior staged health sources and dirty main-consolidation ledger in a private additive backup, verify copies/index blobs, fast-forward the approved commit (5a69a927c4335441939ebd76dc10bf6b200a8d1b, tree f793ceaf7df30daf529963d901c0850e0266daa4), run fresh foreground test verification (17 focused / 83 modular regression PASS), and update main-consolidation ledger. Retained all pre-existing ledger content and history.
2. [x] Closed: extracted pure evaluate_adb_probe_result into scripts/f3_guard/probe_eval.py with scripts/f3_guard/tests/test_probe_eval.py. Observed RED before production, then 14 focused / 97 regression PASS. Native review approved and ACK consumed before commit 499151df97a90aad4040f923f877fb9fdc7aaaff; independent post-commit tests, hashes, and AST verification passed.
3. [ ] In progress: partitioned extraction of probe_lifecycle (budget limit <=400 changed lines per unit):
   - 3a. [ ] In progress: base ProbeLifecycle coordinator (clock, start, poll, step_probe, record_probe_result, state properties) with constructor-level dependency injection and 11 unit tests. Exposes minimal current API; does not claim runtime integration nor cleanup/health execution without methods.
   - 3b. [ ] Pending: slice 2 restoration of health checks (check_health) and teardown (cleanup_teardown) with deferred test coverage restored test-first (authentic RED before production restore) after slice 3a RDD review, ACK, and commit.

## Evidence
Health parser: 17 focused / 83 regression PASS previously observed; review-f16d0c2a57c361ae approved and ACK burned; commit tree f793ceaf7df30daf529963d901c0850e0266daa4.
Health integration and probe evaluation are verified and committed. The separate runtime wiring unit remains pending. The guard as a whole remains incomplete. Documentation has no meaningful RED; verify structurally and retain historical content.
Probe evaluation (task 2 closed; runtime wiring not included):
- RED: ModuleNotFoundError: No module named 'scripts.f3_guard.probe_eval' (1 error) observed before production creation.
- GREEN: 14 focused tests PASS (scripts.f3_guard.tests.test_probe_eval) in 0.022s.
- Regression: 97 modular tests PASS (discover -s scripts/f3_guard/tests -p 'test_*.py') in 0.034s.
- Source budget: 44 lines probe_eval.py + 236 lines test_probe_eval.py = 280 lines (budget <= 400 lines).
- File hashes:
  - fa859bf6d99d94214d2590ae81fe7369d6bbdde6fd9f425491c667a273c56c9e scripts/f3_guard/probe_eval.py
  - ee692b66c447ccdaaa256c0b6f482e501a207a0817b3af1f05992f90e70d2450 scripts/f3_guard/tests/test_probe_eval.py
- AST verification: function body and signature for evaluate_adb_probe_result match monolith AST identically.
- Native gate: review-864e0f0f50a86d2d approved; exact ACK consumed (authority burned) before commit 499151df97a90aad4040f923f877fb9fdc7aaaff. Commit tree 8218ee40f6267df63b22e11c8aa66b8858760275 equals the frozen approved candidate (329 changed lines including ledgers).
- Independent verifier muxkw2p9: 14 focused / 97 regression PASS, exit 0; source hashes and original AST match. Its workspace-change warning is accounted for by the parent-authored post-commit ledger update (+3/-2); no source changes occurred. Runtime wiring remains pending. This closure update is documentary and outside the approved source candidate.
Probe lifecycle (task 3 split, slice 3a in progress, commit pending):
- Historical full candidate: RED (ModuleNotFoundError: No module named 'scripts.f3_guard.probe_lifecycle', 1 error) observed before production creation; 14 focused / 111 modular regression PASS; 487 source lines (181 probe_lifecycle.py + 306 test_probe_lifecycle.py; 507 total with 20 ledger lines vs HEAD). Preserved in backup.
- Slice 3a base coordinator: 11 focused tests PASS (scripts.f3_guard.tests.test_probe_lifecycle); 108 modular regression PASS (discover -s scripts/f3_guard/tests -p 'test_*.py'). Exposes minimal base API without stubbing cleanup/health.
- Source budget: 139 lines probe_lifecycle.py + 210 lines test_probe_lifecycle.py = 349 lines (candidate total <= 400 lines).
- File hashes:
  - c03bce238e4b7b881d55e5b42b28e0157feafeb2055611fd524f9baa381054b4 scripts/f3_guard/probe_lifecycle.py
  - 3f396c7f54982d972e574e343b0b487f1a4502e5484d86e0e2033cb60cc2cc41 scripts/f3_guard/tests/test_probe_lifecycle.py
- Deferred slice 3b coverage: test_check_health_matrix, test_cleanup_teardown_return_false_and_exceptions, test_cleanup_teardown_adapter_diagnostics, and cleanup assertions in adapter tests deferred to slice 2.
- Gate status: slice 3a candidate open, native RDD review, ACK, and commit pending.
