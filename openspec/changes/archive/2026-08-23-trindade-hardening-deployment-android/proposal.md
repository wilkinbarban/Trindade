# Proposal: Harden Trindade Before Publication and Android Delivery

## Intent

Protect the active production system while restoring trustworthy release evidence. Delivery proceeds through defect remediation, hardening, source publication, then an online Android APK. Implementation requires planning approval.

## Scope

### First Implementation-Ready Boundary
- Back up SQLite and associated files, verify integrity, and prove isolated restoration before any mutation.
- Align authentication, test fixtures, seed/reference data, exports, and documentation with approved truth while preserving real data.
- Replace fixed fresh-install administrator credentials with a secure first-run setup assistant.
- Restore a fail-fast backend baseline and classify residual defects by root cluster.

### Gated Future Stages
1. Harden migrations, dependencies, security, quality, observability, accessibility, mobile web, and operations.
2. Establish valid Git, scan publication candidates, add CI/versioning, and publish source after approval.
3. After stable HTTPS compatibility, deliver online feature parity through a signed downloadable Capacitor APK.

### Non-Goals
- Production reset, destructive migration, or deployment during the first boundary.
- Treating GitHub publication as initial production deployment.
- Offline mutation/synchronization, Google Play distribution, or native client rewrite.
- Unnecessary enterprise-scale process or infrastructure.

## Capabilities

### New Capabilities
- `production-data-recovery`: SQLite/file backup, integrity checks, isolated restore proof, and rollback controls.

### Modified Capabilities
- `auth`: Secure first-run administrator setup and canonical authentication behavior.
- `catalog-seed-data`: Separate fresh-install data from preservation-safe upgrades.
- `foundation`: Gate all production mutations on recovery proof and versioned, non-destructive evolution.

## Approach and Gates

| Stage | Exit gate |
|---|---|
| 1. Recovery and truth | Restore passes; production remains unchanged; auth/data baseline is green or exceptions are approved. |
| 2. Hardening | Clean-checkout CI, security/data review, migration rehearsal, and mobile/accessibility evidence pass. |
| 3. Publication | Valid Git decision, secret/data scan, reviewable auto-chain, and explicit publication approval. |
| 4. Android | Stable HTTPS backend, parity evidence on real devices, signed APK, and rollback/update procedure. |

Auto-chain root-cluster work below 400 changed lines per review with named verification and rollback.

## Affected Areas

Backend DB/auth/fixtures, frontend/public assets, Docker, documentation, and future GitHub/Android configuration.

## Risks and Rollback

Wrong canonical truth can preserve defects; migrations can damage data; publication can leak secrets; premature APK delivery can amplify failures. Stop on failed gates. Restore into an isolated target first; roll back each chained slice; never reset production as recovery.

## Success Criteria

- [ ] Backup integrity and isolated restoration are reproducibly proven before mutation.
- [ ] Existing production records and non-expired associated files remain intact; policy-expired report photos MAY be deleted by retention cleanup.
- [ ] Fresh installs securely create the first administrator.
- [ ] Each stage advances only after its gate and explicit planning approval.
- [ ] Android v1 provides hosted-backend parity through a downloadable APK.
