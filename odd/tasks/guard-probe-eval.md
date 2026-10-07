# Guard health integration and probe evaluation

## Authorization
User authorizes lossless integration of approved commit 5a69a927c4335441939ebd76dc10bf6b200a8d1b into feature/principal-review-chain, ledger update, and next guard unit. No push, main changes, destructive reset, worktree removal, or emulator operations. Every source unit must remain <=400 changed lines with tests before implementation and native approval/ACK before local commit.

## Tasks
1. [x] Closed: preserve prior staged health sources and dirty main-consolidation ledger in a private additive backup, verify copies/index blobs, fast-forward the approved commit (5a69a927c4335441939ebd76dc10bf6b200a8d1b, tree f793ceaf7df30daf529963d901c0850e0266daa4), run fresh foreground test verification (17 focused / 83 modular regression PASS), and update main-consolidation ledger. Retained all pre-existing ledger content and history.
2. [x] Closed: extracted pure evaluate_adb_probe_result into scripts/f3_guard/probe_eval.py with scripts/f3_guard/tests/test_probe_eval.py. Observed RED before production, then 14 focused / 97 regression PASS. Native review approved and ACK consumed before commit 499151df97a90aad4040f923f877fb9fdc7aaaff; independent post-commit tests, hashes, and AST verification passed.
3. [x] Closed: partitioned extraction of probe_lifecycle (budget limit <=400 changed lines per unit):
   - 3a. [x] Closed in c89d40da1fdc9a6e85fdd4856ba0c4fc7b6afc8b after native approval/ACK and independent 11/108 PASS: base ProbeLifecycle coordinator (clock, start, poll, step_probe, record_probe_result, state properties) with constructor-level dependency injection and 11 unit tests. Exposes minimal current API; does not claim runtime integration nor cleanup/health execution without methods.
   - 3b. [x] Closed in 6cce0a5 after native approval/ACK and independent 15/112 PASS: slice 2 restoration of health checks (check_health) and teardown (cleanup_teardown) with deferred test coverage restored test-first (authentic RED before production restore) after slice 3a RDD review, ACK, and commit.
     - Correction R3-STALE-BYPASS authorized (native review-aa5aad44437d02f9 plan): remove UNKNOWN staleness exemption in check_health against last_probe_mono. Completed TDD new test (RED), 1-line production fix (GREEN), correction 47/77 changed lines vs frozen 8c62f356, candidate 187/400 changed lines vs c89d40d.

4. [ ] In progress: partitioned extraction of probe transport (budget limit <=400 changed lines per unit):
   - 4a. [x] Closed in 816bd112fcd7c07b7a0613c7115ff6efd9d8e769 after native approval/ACK and independent 6/118 PASS: extract StreamDrainBuffer bounded I/O draining/decoding helper into scripts/f3_guard/stream_buffer.py with hermetic unit tests first in scripts/f3_guard/tests/test_stream_buffer.py. Forecast ~205 changed lines (ESTIMATE, not verified). Preserves exact chunk_size=16384, max_chunks_per_poll=16, non-blocking drain, combined max_bytes quota overflow, unexpected OSError vs BlockingIOError/InterruptedError handling, and independent decoding of accumulated byte slices with UTF-8 replacement (errors='replace'; no incremental decoder or buffer consumption).
   - 4b. [ ] In progress: partitioned extraction of probe transport (budget limit <=400 changed lines per unit):
     - 4b.1 [ ] In progress: extract complete abstract ProbeCommandHandle interface into scripts/f3_guard/probe_handle.py and 4-assertion contract test in scripts/f3_guard/tests/test_probe_handle.py. Forecast ~70 changed lines (ESTIMATE, not verified).
     - 4b.2 [ ] Pending: extract SubprocessProbeHandle transport into scripts/f3_guard/probe_handle.py composing StreamDrainBuffer with process lifecycle, pidfd signaling, and cleanup, restoring 11 deferred tests. Forecast ~376 changed lines (ESTIMATE, not verified). Full candidate preserved in private backup `probe-handle-composed-20261007T112942Z`. Native RDD approval/ACK required before local commit; no push/main changes or real subprocess tests. Adapter validation/coordinator and runtime wiring remain separate.

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
Probe lifecycle (task 3 split, both slices closed; runtime integration pending):
- Historical full candidate: RED (ModuleNotFoundError: No module named 'scripts.f3_guard.probe_lifecycle', 1 error) observed before production creation; 14 focused / 111 modular regression PASS; 487 source lines (181 probe_lifecycle.py + 306 test_probe_lifecycle.py; 507 total with 20 ledger lines vs HEAD). Preserved in backup.
- Slice 3a base coordinator: 11 focused tests PASS (scripts.f3_guard.tests.test_probe_lifecycle); 108 modular regression PASS (discover -s scripts/f3_guard/tests -p 'test_*.py'). Exposes minimal base API without stubbing cleanup/health.
- Source budget: 139 lines probe_lifecycle.py + 210 lines test_probe_lifecycle.py = 349 lines (candidate total <= 400 lines).
- File hashes:
  - c03bce238e4b7b881d55e5b42b28e0157feafeb2055611fd524f9baa381054b4 scripts/f3_guard/probe_lifecycle.py
  - 3f396c7f54982d972e574e343b0b487f1a4502e5484d86e0e2033cb60cc2cc41 scripts/f3_guard/tests/test_probe_lifecycle.py
- Deferred slice 3b coverage: test_check_health_matrix, test_cleanup_teardown_return_false_and_exceptions, test_cleanup_teardown_adapter_diagnostics, and cleanup assertions in adapter tests deferred to slice 2.
- Slice 3a gate: review-21126d6eaa874a5b approved; exact ACK burned before commit c89d40da1fdc9a6e85fdd4856ba0c4fc7b6afc8b, tree afe60d3159df96287f0f5932866784333d640c15 (371 changed lines). Independent verifier muxmglb6 confirmed 11/108 PASS, exit 0, clean workspace, retained AST and complete private backup integrity.
- Slice 3b baseline: complete candidate preserved in .git/consolidation-recovery/lifecycle-full-20261007T043254Z-slice1; manifest SHA-256 70156ef9a2f4fbe08739fa8a48ad39d1b631d7b36c815085cc26a646b02f5040. Restore deferred tests first, observe RED against base methods, then restore production. Native review/ACK and commit subsequently completed for this second candidate (closure below).
- Slice 3b restoration evidence:
  - Historical restoration baseline: RED (5 AttributeError), 14 focused / 111 regression PASS; hashes c37780ac13ce6589d2516dfe6b3929c506c0367525939fefd822d3b8c1232a75 (scripts/f3_guard/probe_lifecycle.py), 569ea548c04d57bb8fed270798f13a02e62bd49ced35a3e87e5e640ac0abc8c7 (scripts/f3_guard/tests/test_probe_lifecycle.py); AST matched backup (historical baseline notes preserved; authorized semantic exception for check_health correction).
  - Correction R3-STALE-BYPASS evidence:
    - RED: authentic AssertionError in test_check_health_stale_while_probe_running_unknown (14 pass, 1 failure, exit 1) with production unchanged.
    - GREEN: 15 focused tests PASS (scripts.f3_guard.tests.test_probe_lifecycle) in 0.081s.
    - Regression: 112 modular tests PASS (discover -s scripts/f3_guard/tests -p 'test_*.py') in 0.028s.
    - Hashes: dc1171f47484c5cdf20bb6f02a8869455e09936fde9c3d58c5b2a91ddfc7e17b (scripts/f3_guard/probe_lifecycle.py), 01e6e085ac285298aafa7c5ea8c69136f3eb867f6d01a8670bdba7eb379ea6ac (scripts/f3_guard/tests/test_probe_lifecycle.py).
    - AST verification: check_health updated with minimal removal of UNKNOWN exemption; all other methods match c89d40d or backup identically.
  - Gate closed: review-aa5aad44437d02f9 targeted validation approved; native acknowledge-approved returned authority burned before commit 6cce0a51e857873744f73d11b10b9df8224dc89a. Exact approved tree 195072c298a0e2496bf1fc61a5a09a100cfc55b1 verified before commit; 183 additions + 4 deletions = 187/400 changed lines, correction 47/77.
  - Independent verifier muxxwr80-63-nrd4: fresh foreground Python -B tests 15 focused / 112 regression PASS, exit 0; hashes, HEAD/tree, clean workspace before/after, private manifest and all six backup files verified. Nine base methods and 14 historical tests retain AST; cleanup matches backup; check_health only removes UNKNOWN exemption. New test accepts 10.0 s, rejects 10.001 s before 20 s deadline without refreshing last_probe_mono. Initial verifier AST script selected the wrong operand, was corrected and passed; no candidate failure or writes.
  - R3-OVERWRITE remains a non-blocking informational warning for separate future work; receipt stands. Runtime wiring remains separate and unverified. This post-commit closure is documentation only, outside the approved source candidate.
Probe transport (task 4 split into 4a StreamDrainBuffer and 4b SubprocessProbeHandle):
- Historical full candidate: RED observed before production creation; 12 focused / 124 modular regression PASS; 434 source lines (200 probe_handle.py + 234 test_probe_handle.py; 449 total with 15 ledger lines vs HEAD 587ee440). Exceeded 400 changed line limit by 49 lines without code-golfing.
- Additive private backup: verified and preserved in `/home/wilkin/proyectos/Trindade/.git/consolidation-recovery/probe-handle-oversized-candidate-20261007T105151Z` (dir 0700, files 0600) with complete MANIFEST.txt (SHA-256 3541654e812390bfe8501fd62dc4ac49ba2c17e7c9b69134c69ae743dd1cca7a), git-status.txt, git-index-stage.txt, doc-diff.txt, probe_handle.py (SHA-256 0e57f4f3af8f2e319247262314145bfa9a6eee8069c2d57aa9d47d58a17e7aaa), test_probe_handle.py (SHA-256 3af0f52a120789b608b9ae0a7bc8d616af74a69a2303e2ba403cbe7eb0c00fd5), and odd-tasks-guard-probe-eval.md. Untracked oversized workspace files unlinked after backup verification (chronology of workspace unlinking is writer-observed, not independently proven).
- Read-only base sources intact:
  - e30a00d32a7f4f85c74e1476e7694878bfeb6e50d6ce7fa67644315f9106ea92 /home/wilkin/proyectos/Trindade/scripts/f3_emulator_guard.py
  - 7048aa9f40433a4ebd1fb959bbb085bcd86f6b3e030e528bbc05daf2c9dc3296 /home/wilkin/proyectos/Trindade/scripts/test_f3_emulator_guard.py
- Deferred to Slice 4b (8 assertions from historical candidate): base interface NotImplementedError, platform missing pidfd fail-closed, successful spawn/anchoring, pidfd_open failure child retention & pipe fd cleanup, popen failure pipe fd cleanup & raise, descendant-pipe open detection, pidfd-only SIGTERM (no SIGKILL / proc.send_signal), cleanup close failures & reap checks.
- Slice 4a StreamDrainBuffer:
  - RED: ModuleNotFoundError: No module named 'scripts.f3_guard.stream_buffer' (1 error) observed before initial production creation.
  - GREEN: 6 focused tests PASS (scripts.f3_guard.tests.test_stream_buffer) in 0.002s. Restored isolated BlockingIOError coverage (io_error None, overflow False, callbacks empty, fresh buffers) and added isolated InterruptedError same-invariant coverage against unchanged production algorithm (coverage restoration GREEN; no artificial RED); kept mixed unexpected OSError with termination callback assertion.
  - Regression: 118 modular tests PASS (discover -s scripts/f3_guard/tests -p 'test_*.py') in 0.025s (baseline 112 + 6 new).
  - Source budget: 97 lines stream_buffer.py + 156 lines test_stream_buffer.py = 253 source lines; 274 total changed lines vs HEAD 587ee440 (budget <= 400 lines).
  - File hashes:
    - d807e63bcdd441099b1aaba5d0862bcd9d40d7d4a9fef6a010125607c6b97f03 scripts/f3_guard/stream_buffer.py
    - d6816fdff21fdb3ce0de60d5409971aca3141007a82bde99e0f94b0cad487107 scripts/f3_guard/tests/test_stream_buffer.py
  - AST equivalence: worker-asserted via direct inspection and read-only Python AST check mapping monolith lines 1211-1249 (_drain_pipes, read_available) to StreamDrainBuffer under composition DI substitutions (os.read -> self._read_fn, self.terminate() -> self._on_terminate(), self._r_out/_r_err -> r_out/r_err arguments, self._out_buf/_err_buf -> self.out_buf/self.err_buf); no automated independent AST verification claimed.
  - Native gate closed: review-3e384a6947183b5e approved; native acknowledgement completed with authority burned before commit 816bd112fcd7c07b7a0613c7115ff6efd9d8e769. git write-tree matched approved tree 21a23ebde10794dcf2ee8932e31af23668ae3638 before commit (274 insertions, 3 files).
  - Independent post-commit verifier muy02vtt-6b-575e confirmed fresh foreground 6 focused / 118 regression PASS, exit 0; exact HEAD/tree, hashes, restored isolated transient-error assertions, retained helper assertions, backup manifest and original monolith hashes. Workspace clean before/after. R3-001 (86-89) and R3-002 (72-78) are non-blocking informational warnings for separate later work; no reopening. Slice 4b and runtime integration remain pending. This documentary closure is outside the approved source candidate.
- Slice 4b partition (Slice 4b split into 4b.1 and 4b.2 per parent instruction):
  - Exclusive private additive backup: verified in `/home/wilkin/proyectos/Trindade/.git/consolidation-recovery/probe-handle-composed-20261007T112942Z` (dir 0700, files 0600) with MANIFEST.txt (SHA-256 89731760a5b94e5876f4477e94056b5938f2ff6d318b4b2ed50371d08130f203), git-status.txt, git-index-stage.txt, doc-diff.txt, probe_handle.py (SHA-256 2908a692adba218eac7cdcba35826c5714a1d49412991054e3843e6405ec1c24, 173 lines), test_probe_handle.py (SHA-256 3af0f52a120789b608b9ae0a7bc8d616af74a69a2303e2ba403cbe7eb0c00fd5, 234 lines), and odd-tasks-guard-probe-eval.md (SHA-256 652abbf7363bd8365a804a80d9a789a87e2ad91ac5f262059d0b516e29a92d49).
  - Slice 4b.1 ProbeCommandHandle:
    - RED: ModuleNotFoundError: No module named 'scripts.f3_guard.probe_handle' (1 error) observed running PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest scripts.f3_guard.tests.test_probe_handle before production implementation.
    - GREEN: 1 focused test PASS (scripts.f3_guard.tests.test_probe_handle) in 0.000s verifying all 4 base methods (poll, read_available, terminate, cleanup) raise NotImplementedError.
    - Regression: 119 modular tests PASS (discover -s scripts/f3_guard/tests -p 'test_*.py') in 0.017s (baseline 118 + 1 new).
    - Source budget: 30 lines probe_handle.py + 30 lines test_probe_handle.py = 60 source lines; 77 total changed lines vs HEAD (forecast ~70 lines incl docs; well under <=400 budget).
    - File hashes:
      - 089ed8835ff1fd1596399a76303e1fdde4c2e9b785319cf32ba81d2d87573611 scripts/f3_guard/probe_handle.py
      - fffbce5164dedc0fcae52ba0ee4c260c4a9d85d683b5e1e20fdb8eeabdc6a554 scripts/f3_guard/tests/test_probe_handle.py
    - AST equivalence: ProbeCommandHandle class AST identical to monolith scripts/f3_emulator_guard.py lines 1113-1138.
    - Deferred to 4b.2: 11 SubprocessProbeHandle test cases (missing pidfd fail-closed, spawn/anchoring, pidfd_open failure, popen failure, bounded drain calls, quota overflow SIGTERM, blocking vs unexpected oserror, descendant pipe open, utf-8 replacement, pidfd-only SIGTERM, cleanup close failure/repeat) and full SubprocessProbeHandle implementation composing StreamDrainBuffer, preserved intact in private backup.
    - Native gate: pending parent review, RDD ACK, and local commit; no task closed.
