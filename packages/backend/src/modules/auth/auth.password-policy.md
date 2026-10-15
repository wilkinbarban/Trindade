# New-password policy

Every password the system accepts or sets requires at least 15 visible Unicode graphemes
(`Intl.Segmenter`), at most 72 bytes of the original UTF-8 input, and must not be one of the
curated weak passwords in `weak-passwords.ts`. Spaces, case and Unicode composition are
preserved; no trimming, normalization or silent bcrypt truncation. `passwordPolicy` in
`auth.schema.ts` is the single definition, so the three rules cannot drift between callers;
admin imports it rather than restating it.

Errors: `Password must be at least 15 visible graphemes`, `Password must not exceed 72 bytes`,
`Password is too common; choose another passphrase`.

Covered paths: public registration, self-service password change (the `currentPassword` being
proved stays permissive), operator provisioning through `db:provision-admin`, and admin user
creation or update (the update field stays optional, so "no new password" still works).

Screening is whole-value and case-insensitive, so a passphrase that merely contains a listed
word is accepted. The list is short and hand-written on purpose: there is no licence review for
a third-party corpus and no update process for one, so it catches the product name, the obvious
sequences and the keyboard walks rather than claiming broad coverage. Growing it means editing
that file, not the rule; a test asserts every entry is long enough to be reachable and fits the
byte bound, so the list cannot drift into entries the rule never reaches.
Declared limits: long runs of one repeated character are only screened where listed, and leet or
digit-suffix variants of a listed word are accepted. Those are choices, not oversights.

Not covered here: the web admin form still pre-validates 8 characters, so a rejected password
fails at the server with a clear message instead of being prevented in the browser (deferred to
the T09/T13 integration). `loginSchema` deliberately accepts any password so accounts created
under earlier rules can still sign in.
