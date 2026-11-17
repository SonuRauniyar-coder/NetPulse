-- Access tokens issued before this moment are refused (set when a password is reset).
alter table app_user add column sessions_valid_after timestamptz;
