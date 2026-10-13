# Public photo link lifetime

A photo's public token is issued with the upload, so it is live for 24 hours from that
moment and stops resolving afterwards; `getPhotoByPublicToken` returns nothing, which both
public routes use, so the same rule covers `/p/:token` and `/api/reports/photos/public/:token`.
An unreadable `created_at` is treated as not live, never as live.

This is the only link mechanism: the token is opaque, random and stored, so the server can
revoke it and no signing key needs rotation. The bound is the earlier of this window and the
30-day file retention — the retention deletes the row, so a link can never outlive its file.

Consequences worth stating plainly: re-copying a shared link later does not extend it, and a
link shared on the previous day is already dead unless the photo was uploaded that day. Photos
stay reachable to signed-in users through the authenticated photo route for the full retention
window. No column stores a per-emission timestamp, so "issue a fresh 24-hour link" is not
implemented; adding it would need its own stored issuance.
