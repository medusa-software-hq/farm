# Migrations

**Additive only.** A migration must never break a release still running against the previous schema —
that invariant is what lets the API and the worker deploy independently against the one database they
share.

Allowed in a migration: new tables, nullable columns (or with a default), indexes, widened types,
relaxed constraints. **Never in a single migration:** dropping or renaming a column/table, adding
`NOT NULL` without a default, narrowing a type, or a constraint that existing rows or in-flight
writes could violate.

Removing a column is a *later* migration, once no running release still touches it. A rename is three
releases: add the new column and dual-write, backfill, then drop the old.
