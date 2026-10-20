-- People who log in. Organizers are customers who may also create events.
-- E-mails are stored lower case, so the unique constraint also rejects the same address in another case.
CREATE TABLE customers (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_customers_email UNIQUE (email),
    CONSTRAINT ck_customers_email_lower_case CHECK (email = lower(email)),
    CONSTRAINT ck_customers_role CHECK (role IN ('CUSTOMER', 'ORGANIZER'))
);
