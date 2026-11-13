create table file_metadata (
    id                text primary key,
    file_id           text not null,
    file_name         text,
    project_id        text,
    workspace_id      text,
    file_size         bigint,
    file_type         text,
    extension         text,
    blob_id           text,
    storage_location  text,
    uploaded_by       text,
    uploader_email    text,
    uploader_username text,
    uploaded_at       timestamptz,
    status            text,
    is_deleted        boolean default false,
    deleted_at        timestamptz,
    deleted_by        text
);

create unique index file_metadata_file_id on file_metadata (file_id);
create index file_metadata_project on file_metadata (project_id);
create index file_metadata_workspace on file_metadata (workspace_id);
create index file_metadata_uploaded_by on file_metadata (uploaded_by);
create index file_metadata_deleted_at on file_metadata (deleted_at) where is_deleted is true;

create table invitation (
    id               text primary key,
    invitation_token text not null,
    invitee_email    text,
    workspace_id     text,
    workspace_name   text,
    invited_by       text,
    invited_by_email text,
    role             text,
    status           text,
    created_at       timestamptz,
    accepted_at      timestamptz
);

create unique index invitation_token on invitation (invitation_token);
create index invitation_workspace on invitation (workspace_id, status);
create index invitation_invitee on invitation (lower(invitee_email), workspace_id);
