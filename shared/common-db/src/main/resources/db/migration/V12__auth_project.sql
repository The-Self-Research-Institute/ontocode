create table project (
    id           text primary key,
    project_id   text not null,
    name         text,
    description  text,
    workspace_id text,
    owner_id     text,
    status       text,
    tags         text[] not null default '{}',
    file_ids     text[] not null default '{}',
    visibility   text,
    created_at   timestamptz,
    updated_at   timestamptz,
    is_deleted   boolean default false,
    deleted_at   timestamptz,
    deleted_by   text
);

create unique index project_project_id on project (project_id);
create index project_workspace on project (workspace_id);
create index project_owner on project (owner_id);
create index project_deleted_at on project (deleted_at) where is_deleted is true;

create table project_member (
    project_row_id        text not null references project (id) on delete cascade,
    position              integer not null,
    user_id               text,
    username              text,
    email                 text,
    role                  text,
    joined_at             timestamptz,
    workspace_editor_link text,
    primary key (project_row_id, position)
);

create index project_member_user on project_member (user_id);
create index project_member_email on project_member (lower(email));

create table project_file (
    project_row_id    text not null references project (id) on delete cascade,
    position          integer not null,
    file_id           text,
    file_name         text,
    file_size         bigint,
    file_type         text,
    extension         text,
    uploaded_by       text,
    uploader_username text,
    uploader_email    text,
    uploaded_at       timestamptz,
    status            text,
    primary key (project_row_id, position)
);

create index project_file_file_id on project_file (file_id);
