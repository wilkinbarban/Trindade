# Login comparison admission

Missing users undergo bcrypt comparison against a public dummy hash (work factor 10).
Inactive users still undergo comparison but always receive the same neutral 401.
All comparisons share admission and release their slot in `finally`; saturation
returns the existing 429 and Retry-After response, including for missing users.
This removes the early-return discrepancy, not every timing side channel: legacy
hashes may have different work factors. No constant-time guarantee is claimed.
