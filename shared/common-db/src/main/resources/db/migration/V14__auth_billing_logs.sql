create table stripe_event (
    id              text primary key,
    stripe_event_id text not null,
    event_type      text,
    status          text,
    error_message   text,
    received_at     timestamptz,
    processed_at    timestamptz
);

create unique index stripe_event_stripe_event_id on stripe_event (stripe_event_id);
create index stripe_event_type_received on stripe_event (event_type, received_at);

create table email_log (
    id                     text primary key,
    log_key                text not null,
    type                   text,
    status                 text,
    kind                   text,
    days_before            integer,
    period_end             timestamptz,
    email                  text,
    user_id                text,
    username               text,
    stripe_invoice_id      text,
    stripe_customer_id     text,
    stripe_subscription_id text,
    amount_paid            bigint,
    currency               text,
    created_at             timestamptz,
    sent_at                timestamptz,
    failed_at              timestamptz,
    error_message          text
);

create unique index email_log_key on email_log (log_key);

create table stripe_unmatched_invoice (
    id                     text primary key,
    invoice_id             text,
    stripe_customer_id     text,
    stripe_subscription_id text,
    customer_email         text,
    discovered_email       text,
    amount_paid            bigint,
    amount_due             bigint,
    currency               text,
    hosted_invoice_url     text,
    recorded_at            timestamptz,
    status                 text
);

create index stripe_unmatched_invoice_invoice on stripe_unmatched_invoice (invoice_id);
