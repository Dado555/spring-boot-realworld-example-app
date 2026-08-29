-- step 8.7 rollback drill: a genuinely backward-compatible "expand" change (nullable,
-- additive), deployed alongside a deliberately broken config value in the same release.
-- the point: reverting the image does not revert this migration - it stays applied.
alter table users add column rollback_drill_marker text;
