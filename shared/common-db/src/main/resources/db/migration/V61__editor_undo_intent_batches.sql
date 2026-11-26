alter table undo_intent add column batch_change_id text;
alter table undo_intent add column applied_batches integer not null default 0;
alter table undo_intent add column batch_triples integer not null default 0;
