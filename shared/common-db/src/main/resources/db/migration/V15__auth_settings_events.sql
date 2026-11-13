create table system_settings (
    id                           text primary key,
    maintenance_mode_enabled     boolean not null default false,
    maintenance_message          text,
    maintenance_allowed_domains  text[] not null default '{}',
    maintenance_allowed_emails   text[] not null default '{}',
    maintenance_schedule_enabled boolean not null default false,
    maintenance_all_day_date     text,
    maintenance_start_time       timestamptz,
    maintenance_end_time         timestamptz,
    maintenance_daily_enabled    boolean not null default false,
    maintenance_daily_start_time text,
    maintenance_daily_end_time   text,
    maintenance_daily_timezone   text,
    enterprise_domains           text[] not null default '{}',
    enterprise_emails            text[] not null default '{}',
    updated_at                   timestamptz,
    updated_by                   text
);

create table plan_feature_config (
    id                      text primary key,
    plan_id                 text not null,
    monthly_price           integer not null default 0,
    annual_discount_percent integer not null default 0,
    features                text[],
    limitations             text[],
    max_members             integer not null default 0,
    updated_at              timestamptz
);

create unique index plan_feature_config_plan_id on plan_feature_config (plan_id);

create table desktop_download_event (
    id          text primary key,
    platform    text,
    client_os   text,
    version     text,
    filename    text,
    ip_hash     text,
    user_agent  text,
    referer     text,
    event_type  text,
    recorded_at timestamptz
);

create index desktop_download_event_platform on desktop_download_event (platform, event_type);
create index desktop_download_event_client_os on desktop_download_event (client_os);

create table audit_event (
    id         text primary key,
    username   text,
    event_type text,
    ip_address text,
    event_time timestamptz,
    metadata   jsonb
);

create index audit_event_username on audit_event (username, event_time);
create index audit_event_type on audit_event (event_type, event_time);
