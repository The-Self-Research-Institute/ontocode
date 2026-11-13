alter table stored_file add column storage text not null default 'database';

create index stored_file_storage on stored_file (storage);
