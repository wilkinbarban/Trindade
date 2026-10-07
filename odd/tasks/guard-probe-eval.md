# Guard health integration and probe evaluation

## Authorization
User authorizes lossless integration of approved commit 5a69a927c4335441939ebd76dc10bf6b200a8d1b into feature/principal-review-chain, ledger update, and next guard unit. No push, main changes, destructive reset, worktree removal, or emulator operations. Every source unit must remain <=400 changed lines with tests before implementation and native approval/ACK before local commit.

## Tasks
1. [x] Closed: preserve prior staged health sources and dirty main-consolidation ledger in a private additive backup, verify copies/index blobs, fast-forward the approved commit (5a69a927c4335441939ebd76dc10bf6b200a8d1b, tree f793ceaf7df30daf529963d901c0850e0266daa4), run fresh foreground test verification (17 focused / 83 modular regression PASS), and update main-consolidation ledger. Retained all pre-existing ledger content and history.
2. [ ] In progress: extract pure evaluate_adb_probe_result into scripts/f3_guard/probe_eval.py with scripts/f3_guard/tests/test_probe_eval.py (implemented and tested; native review, ACK, and commit pending). Observe RED before production; run focused and full modular guard regression with Python -B. Native review and ACK precede commit.
3. [ ] Map and implement a separate bounded wiring unit only after probe_eval closes; characterize monolith interactions first, preserving original scripts. Stop for unresolved semantics or scope.

## Evidence
Health parser: 17 focused / 83 regression PASS previously observed; review-f16d0c2a57c361ae approved and ACK burned; commit tree f793ceaf7df30daf529963d901c0850e0266daa4.
Health integration and fresh regression are verified. Probe evaluation native review, ACK, commit, and the separate wiring unit remain pending. The guard as a whole remains incomplete. Documentation has no meaningful RED; verify structurally and retain historical content.
Probe evaluation (task 2 in progress, commit pending):
- RED: ModuleNotFoundError: No module named 'scripts.f3_guard.probe_eval' (1 error) observed before production creation.
- GREEN: 14 focused tests PASS (scripts.f3_guard.tests.test_probe_eval) in 0.022s.
- Regression: 97 modular tests PASS (discover -s scripts/f3_guard/tests -p 'test_*.py') in 0.034s.
- Source budget: 44 lines probe_eval.py + 236 lines test_probe_eval.py = 280 lines (budget <= 400 lines).
- File hashes:
  - fa859bf6d99d94214d2590ae81fe7369d6bbdde6fd9f425491c667a273c56c9e scripts/f3_guard/probe_eval.py
  - ee692b66c447ccdaaa256c0b6f482e501a207a0817b3af1f05992f90e70d2450 scripts/f3_guard/tests/test_probe_eval.py
- AST verification: function body and signature for evaluate_adb_probe_result match monolith AST identically.
- Gate status: not closed, commit pending parent native review and ACK.
