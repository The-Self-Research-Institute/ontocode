create table class_detail (
    project_id text not null,
    class_iri  text not null,
    details    jsonb,
    partial    boolean not null default false,
    built_at   bigint not null default 0,
    primary key (project_id, class_iri)
);

create table entity_usage (
    project_id text not null,
    entity_iri text not null,
    revision   bigint not null default 0,
    built_at   bigint not null default 0,
    usages     jsonb,
    primary key (project_id, entity_iri)
);
