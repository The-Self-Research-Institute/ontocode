create table stored_file (
    id           text primary key,
    bucket       text        not null,
    filename     text        not null,
    content_type text,
    length       bigint      not null default 0,
    sha256       text        not null default '',
    upload_date  timestamptz not null default now(),
    metadata     jsonb       not null default '{}'::jsonb
);

create index stored_file_bucket_filename on stored_file (bucket, filename);
create index stored_file_bucket_date on stored_file (bucket, upload_date desc);
create index stored_file_meta_project on stored_file ((metadata ->> 'projectId'));
create index stored_file_meta_platform on stored_file ((metadata ->> 'platform'));
create index stored_file_meta_plugin on stored_file ((metadata ->> 'pluginId'));

create table file_chunk (
    file_id text  not null references stored_file (id) on delete cascade,
    n       int   not null,
    data    bytea not null,
    primary key (file_id, n)
);
