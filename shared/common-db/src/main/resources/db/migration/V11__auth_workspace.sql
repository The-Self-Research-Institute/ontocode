create table workspace (
    id                              text primary key,
    workspace_id                    text not null,
    owner_id                        text not null,
    name                            text,
    description                     text,
    subscription_plan               text,
    billing_status                  text,
    billing_interval                text,
    subscription_start_date         timestamptz,
    subscription_end_date           timestamptz,
    subscription_current_period_end timestamptz,
    max_workspaces                  integer,
    max_members                     integer,
    collaboration_enabled           boolean,
    stripe_subscription_id          text,
    pending_checkout_session_id     text,
    pending_checkout_created_at     timestamptz,
    created_at                      timestamptz,
    updated_at                      timestamptz,
    is_deleted                      boolean default false,
    deleted_at                      timestamptz,
    deleted_by                      text
);

create unique index workspace_workspace_id on workspace (workspace_id);
create index workspace_owner on workspace (owner_id);
create index workspace_stripe_subscription on workspace (stripe_subscription_id);
create index workspace_pending_checkout on workspace (pending_checkout_session_id);
create index workspace_deleted_at on workspace (deleted_at) where is_deleted is true;

create table workspace_member (
    workspace_row_id text not null references workspace (id) on delete cascade,
    position         integer not null,
    user_id          text,
    username         text,
    email            text,
    role             text,
    status           text,
    invitation_token text,
    joined_at        timestamptz,
    primary key (workspace_row_id, position)
);

create index workspace_member_user on workspace_member (user_id);
create index workspace_member_email on workspace_member (lower(email));
create index workspace_member_token on workspace_member (invitation_token);
