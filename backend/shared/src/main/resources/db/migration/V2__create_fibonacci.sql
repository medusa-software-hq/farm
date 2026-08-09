-- The worker writes these rows directly; the API reads them. Additive — see README.md.
CREATE TABLE fibonacci (
    n           INTEGER   NOT NULL PRIMARY KEY,
    value       TEXT      NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT now()
);
