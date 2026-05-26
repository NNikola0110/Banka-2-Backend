-- Spark fraud detection output tabela za banka-core (banka2 db).
-- Pokrece se pri prvom inicijalnom boot-u banka2 db kontejnera.
--
-- transaction_anomalies: izlaz Spark fraud detection job-ova (risk_score per
-- transakcija + JSONB feature vector). Supervizori revizuju "pending" redove
-- (review_status IS NULL OR review_status = 'pending'); rezultat revizije se
-- upisuje u review_status ('confirmed' | 'false_positive' | 'closed').

CREATE TABLE IF NOT EXISTS transaction_anomalies (
  id BIGSERIAL PRIMARY KEY,
  transaction_id BIGINT NOT NULL,           -- FK na transactions.id
  risk_score NUMERIC(5,4) NOT NULL CHECK (risk_score >= 0 AND risk_score <= 1),
  features JSONB NOT NULL DEFAULT '{}'::jsonb,
  model_version TEXT NOT NULL,
  computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  reviewed_by TEXT,                          -- supervisor email koji je pregledao
  review_status TEXT,                        -- 'pending', 'confirmed', 'false_positive', 'closed'
  reviewed_at TIMESTAMP,
  CONSTRAINT transaction_anomalies_tx_unique UNIQUE (transaction_id, model_version)
);

CREATE INDEX IF NOT EXISTS idx_anomalies_risk_score ON transaction_anomalies (risk_score DESC, computed_at DESC);
CREATE INDEX IF NOT EXISTS idx_anomalies_pending ON transaction_anomalies (review_status, computed_at DESC) WHERE review_status IS NULL OR review_status = 'pending';
