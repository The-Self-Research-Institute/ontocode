create table swrl_rule (
    id                  text        primary key,
    project_id          text        not null,
    rule_name           text        not null,
    rule_text           text,
    comment             text,
    category            text,
    enabled             boolean     not null default true,
    created_at          timestamptz,
    updated_at          timestamptz,
    created_by          text,
    updated_by          text,
    execution_count     integer     not null default 0,
    last_execution_time bigint,
    last_executed_at    timestamptz,
    validated           boolean     not null default false,
    validation_error    text,
    migrated_at         timestamptz
);

create unique index swrl_rule_project_name on swrl_rule (project_id, rule_name);
create index swrl_rule_project_unmigrated on swrl_rule (project_id) where migrated_at is null;
create index swrl_rule_created on swrl_rule (created_at);
