-- Speed up upserts and SUM by patient
CREATE UNIQUE INDEX IF NOT EXISTS ux_news2_agg_code_pid ON news2_agg(code, patient_id);
CREATE INDEX IF NOT EXISTS ix_news2_agg_pid ON news2_agg(patient_id);

-- Current per-patient NEWS2 projection for fast regional aggregates
CREATE TABLE IF NOT EXISTS patient_news2_current (
	patient_id VARCHAR(64) PRIMARY KEY,
	block_id VARCHAR(128) NOT NULL,
	news2 INT NOT NULL,
	updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_patient_news2_current_block ON patient_news2_current(block_id);
