-- Speed up upserts and SUM by patient
CREATE UNIQUE INDEX IF NOT EXISTS ux_news2_agg_code_pid ON news2_agg(code, patient_id);
CREATE INDEX IF NOT EXISTS ix_news2_agg_pid ON news2_agg(patient_id);
