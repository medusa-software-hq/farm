CREATE TABLE linked_orgs (
    installation_id BIGINT      NOT NULL PRIMARY KEY,
    org_login       TEXT        NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
