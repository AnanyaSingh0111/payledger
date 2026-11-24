-- One wallet per user per currency (project plan, section 2.1).
--
-- V1 is never edited: environments that already ran it would fail Flyway's checksum check.
--
-- The new unique index is added BEFORE the old one is dropped: both start with user_id, and
-- MySQL needs an index on user_id for the fk_accounts_user foreign key at every moment.
--
-- If a user already holds two wallets in the same currency, this migration fails on purpose
-- (merging wallets would rewrite ledger history). Find such rows before deploying with:
--   SELECT user_id, currency, COUNT(*) FROM accounts GROUP BY user_id, currency HAVING COUNT(*) > 1;

ALTER TABLE accounts ADD CONSTRAINT uk_accounts_user_currency UNIQUE (user_id, currency);

-- names are now just labels; the currency identifies the wallet
ALTER TABLE accounts DROP INDEX uk_accounts_user_name;
