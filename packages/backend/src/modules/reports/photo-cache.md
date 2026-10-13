# Confidential photo downloads

All three photo download routes send `Cache-Control: private, no-store` on success:
`/p/:token`, `/api/reports/photos/public/:token`, and `/api/reports/photos/:photoId`.
The authenticated route still requires a valid access token. This change does not
implement signed-link expiry, revocation, retention cleanup or new access controls.
It cannot invalidate copies already cached or downloaded before deployment.
