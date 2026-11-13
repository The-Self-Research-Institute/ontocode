create table app_user (
    id                              text primary key,
    username                        text not null,
    email                           text not null,
    password                        text,
    roles                           text[] not null default '{}',
    enabled                         boolean not null default false,
    verification_token              text,
    verification_token_expiry       timestamptz,
    password_reset_token            text,
    password_reset_token_expiry     timestamptz,
    failed_login_attempts           integer not null default 0,
    lockout_end_time                timestamptz,
    created_at                      timestamptz,
    updated_at                      timestamptz,
    last_login_at                   timestamptz,
    last_opened_project_id          text,
    last_opened_project_name        text,
    last_opened_file_id             text,
    last_opened_file_name           text,
    stripe_customer_id              text,
    stripe_subscription_id          text,
    subscription_status             text,
    subscription_plan_id            text,
    subscription_plan_name          text,
    billing_interval                text,
    subscription_current_period_end timestamptz,
    auto_renew_enabled              boolean not null default true,
    subscription_canceled_at        timestamptz,
    has_used_free_trial             boolean not null default false,
    first_subscription_at           timestamptz,
    pending_billing_interval        text,
    pending_billing_interval_date   timestamptz,
    pending_checkout_session_id     text,
    pending_checkout_created_at     timestamptz,
    subscription_creation_locked_at timestamptz
);

create unique index app_user_username on app_user (username);
create unique index app_user_email_lower on app_user (lower(email));
create index app_user_email on app_user (email);
create index app_user_stripe_customer on app_user (stripe_customer_id);
create index app_user_stripe_subscription on app_user (stripe_subscription_id);
create index app_user_verification_token on app_user (verification_token);
create index app_user_password_reset_token on app_user (password_reset_token);
create index app_user_period_end on app_user (subscription_current_period_end);
