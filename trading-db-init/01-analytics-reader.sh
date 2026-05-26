#!/bin/bash
# Init script za trading_db — kreira analytics_reader read-only user-a.
# Pokrece se SAMO pri prvom inicijalnom boot-u trading_db kontejnera
# (postgres:16-alpine docker-entrypoint automatski poziva sve .sh / .sql
# u /docker-entrypoint-initdb.d/ pri praznoj data dir-i).
#
# analytics_reader se koristi od strane Spark analytics/ML jobs-ova za
# read-only pristup trading_db tabelama (orders, listings, portfolios,
# analytics_daily, price_predictions).

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE USER analytics_reader WITH PASSWORD '${ANALYTICS_READER_PASSWORD}';
  GRANT CONNECT ON DATABASE trading TO analytics_reader;
  GRANT USAGE ON SCHEMA public TO analytics_reader;
  GRANT SELECT ON ALL TABLES IN SCHEMA public TO analytics_reader;
  ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO analytics_reader;
EOSQL
echo "analytics_reader user created."
