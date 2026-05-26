-- Spark analytics output tabele za trading_db.
-- Pokrece se pri prvom inicijalnom boot-u trading_db kontejnera.

-- analytics_daily: agregirani business metrics po danu.
CREATE TABLE IF NOT EXISTS analytics_daily (
  id BIGSERIAL PRIMARY KEY,
  metric_date DATE NOT NULL,
  metric_name TEXT NOT NULL,                    -- npr. 'top_movers', 'sector_perf', 'klijent_activity'
  dimensions JSONB NOT NULL DEFAULT '{}'::jsonb, -- npr. {"symbol": "AAPL", "sector": "tech"}
  value NUMERIC NOT NULL,
  computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT analytics_daily_unique UNIQUE (metric_date, metric_name, dimensions)
);

CREATE INDEX IF NOT EXISTS idx_analytics_daily_metric ON analytics_daily (metric_name, metric_date DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_daily_date ON analytics_daily (metric_date DESC);

-- price_predictions: ML output za N+1 day predikcije.
CREATE TABLE IF NOT EXISTS price_predictions (
  id BIGSERIAL PRIMARY KEY,
  symbol TEXT NOT NULL,
  prediction_date DATE NOT NULL,                -- datum za koji predicira
  predicted_close NUMERIC NOT NULL,
  lower_bound NUMERIC NOT NULL,
  upper_bound NUMERIC NOT NULL,
  model_version TEXT NOT NULL,
  computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT price_predictions_unique UNIQUE (symbol, prediction_date, model_version)
);

CREATE INDEX IF NOT EXISTS idx_price_predictions_symbol_date ON price_predictions (symbol, prediction_date DESC);
