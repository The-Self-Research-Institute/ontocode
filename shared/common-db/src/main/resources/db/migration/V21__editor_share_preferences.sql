create table project_share (
    id                 text primary key,
    project_id         text not null,
    owner_email        text,
    shared_with_emails text[] not null default '{}',
    share_link         text,
    permission         text,
    created_at         timestamptz,
    updated_at         timestamptz
);

create unique index project_share_project on project_share (project_id);
create unique index project_share_link on project_share (share_link);
create index project_share_owner on project_share (owner_email);
create index project_share_shared_with on project_share using gin (shared_with_emails);

create table user_project_preference (
    id         text primary key,
    user_email text not null,
    project_id text not null,
    sync_mode  text,
    updated_at timestamptz
);

create unique index user_project_preference_key on user_project_preference (user_email, project_id);
create index user_project_preference_project on user_project_preference (project_id);
