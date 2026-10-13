# New photo token entropy

New photos use 16 cryptographically random bytes, encoded as 32 hexadecimal characters.
Existing tokens and photos are not rewritten or revoked by this change.
These remain opaque bearer identifiers, not signed or expiring links: signature,
24-hour link lifetime and the file-retention bound still require separate work.
URLs, metadata, ownership checks and the five-photo limit are unchanged.
