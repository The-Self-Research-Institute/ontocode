create table plugin (
    id              text primary key,
    plugin_id       text not null,
    name            text,
    description     text,
    author          text,
    author_email    text,
    latest_version  text,
    category        text,
    keywords        text[],
    license         text,
    repository      text,
    homepage        text,
    icon            text,
    screenshots     text[],
    total_downloads bigint,
    average_rating  double precision,
    review_count    integer,
    verified        boolean,
    deprecated      boolean,
    created_at      timestamptz,
    updated_at      timestamptz
);

create unique index plugin_plugin_id on plugin (plugin_id);
create index plugin_category on plugin (category);
create index plugin_created_at on plugin (created_at);
create index plugin_total_downloads on plugin (total_downloads);

create table plugin_version (
    id                  text primary key,
    plugin_id           text not null,
    version             text not null,
    changelog           text,
    vsix_file_id        text,
    file_size           bigint,
    dependencies        jsonb,
    engines             jsonb,
    entry_point         text,
    deprecated          boolean,
    deprecation_message text,
    downloads           bigint,
    published_at        timestamptz
);

create unique index plugin_version_plugin_version on plugin_version (plugin_id, version);
create index plugin_version_plugin_published on plugin_version (plugin_id, published_at desc);

create table plugin_rating (
    id            text primary key,
    plugin_id     text not null,
    user_id       text not null,
    username      text,
    stars         integer,
    review        text,
    merits        text,
    demerits      text,
    recommended   boolean,
    helpful_count integer,
    created_at    timestamptz,
    updated_at    timestamptz
);

create unique index plugin_rating_plugin_user on plugin_rating (plugin_id, user_id);
create index plugin_rating_user on plugin_rating (user_id);

create table plugin_user_install (
    id                  text primary key,
    plugin_id           text not null,
    user_id             text not null,
    username            text,
    version             text,
    is_active           boolean,
    total_installs      integer,
    first_installed_at  timestamptz,
    last_installed_at   timestamptz,
    last_uninstalled_at timestamptz,
    created_at          timestamptz,
    updated_at          timestamptz
);

create unique index plugin_user_install_plugin_user on plugin_user_install (plugin_id, user_id);
create index plugin_user_install_user on plugin_user_install (user_id);
