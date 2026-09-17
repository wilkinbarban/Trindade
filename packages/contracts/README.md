# @trindade/contracts

The machine-readable API contract for Trindade Massas Operações.

`openapi.json` is an OpenAPI 3.1 document generated from the Zod schemas the backend routes
validate with. It exists so a client author — the Android client in particular — can read the
API surface without building or running the backend, and so a response shape is described in
one place instead of being reimplemented per client.

## Never edit `openapi.json` by hand

Change the schema, then regenerate:

```bash
npm run contracts:generate --workspace=packages/backend
```

The canonical CI gate regenerates the document and fails when the committed artifact differs,
so a schema change cannot be merged without regenerating. Hand-editing it would be overwritten
by the next run and, worse, would describe an API that does not exist.

## Current coverage

The document covers the **field-operations surface only** — the routes a mobile client calls.
Admin and audit routes are deliberately excluded because no mobile client consumes them; they
will join the same registry when one does.

| Surface | Paths | Operations |
| --- | --- | --- |
| `auth` and first-run setup | 8 | 9 |
| `loading` | 10 | 13 |
| `reports` | not yet registered | |
| `dashboard` | not yet registered | |
| **Total** | **18** | **22** |

A route that is registered in the backend but absent from this document is a gap, not a
supported omission. The coverage test that fails on exactly that lands with the final contract
slice, once every field-operations path is registered.

Paths use the OpenAPI `{param}` template form. Fastify spells its routes `:param`, and the
generator does not translate between the two, so the registry does the conversion deliberately:
`:id` is not a valid OpenAPI template and a client generator would not recognise the parameter.
The coverage test normalises `{param}` back to `:param` when comparing against the routes
Fastify actually registered.

## What is and is not guaranteed

Response schemas are Zod schemas, not TypeScript interfaces, and the test suite parses real
responses from the running app against them. A response shape that drifts therefore fails a test
instead of reaching a client. Interfaces could not do this: they describe an intent, not an
observation.

They are `.strict()`, which is what makes that meaningful. A plain `z.object` accepts and
silently strips extra fields, so a route could return something the document does not describe
and nothing would notice. Strict objects make the test prove the server sends *exactly* the
documented fields. The cost is deliberate: adding a response field now requires regenerating the
contract rather than drifting past it.

`details` on the error envelope is intentionally unconstrained. It carries Zod's `flatten()`
output as diagnostic text for a human and is not a stable parsing target.
