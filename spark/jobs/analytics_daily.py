"""
Analytics Daily — racuna agregirane biznis metrike za prosli dan.
- Top movers (top 10 simbola sa najvecim % promenom u 24h)
- Sector performance (group by sector)
- Order volume po simbolu

Cron: 23:00 daily.
Output: trading_db.analytics_daily (dimensions JSONB, value NUMERIC).
"""
import json
import sys
from datetime import datetime, timezone, timedelta
from pyspark.sql import functions as F
from common import build_spark, get_env, read_pg_table, write_pg_table


def main():
    spark = build_spark("AnalyticsDaily")
    target_date = (datetime.now(timezone.utc) - timedelta(days=1)).date()

    jdbc_url = get_env("TRADING_DB_JDBC_URL", required=True)
    user = get_env("ANALYTICS_READER_USER", default="analytics_reader")
    password = get_env("ANALYTICS_READER_PASSWORD", required=True)

    # TODO: schema assumptions — listing_daily_prices (symbol, date, open, close)
    # i orders (ticker_symbol, created_at, quantity, price_per_unit) — verify pre run-a.
    listings = read_pg_table(spark, jdbc_url, "listing_daily_prices", user, password)
    orders = read_pg_table(spark, jdbc_url, "orders", user, password)

    # Top Movers — top 10 simbola po (close - open) / open
    target_str = target_date.isoformat()
    top_movers = (
        listings
        .filter(F.col("date") == F.lit(target_str))
        .withColumn("pct_change", (F.col("close") - F.col("open")) / F.col("open") * 100)
        .orderBy(F.col("pct_change").desc())
        .limit(10)
        .select("symbol", "pct_change")
    )

    top_movers_rows = [
        (target_date, "top_movers", json.dumps({"symbol": r.symbol}), float(r.pct_change))
        for r in top_movers.collect()
    ]

    # Order Volume per symbol
    order_volume = (
        orders
        .filter(F.to_date(F.col("created_at")) == F.lit(target_str))
        .groupBy("ticker_symbol")
        .agg(F.count("*").alias("order_count"),
             F.sum(F.col("quantity") * F.col("price_per_unit")).alias("total_notional"))
    )

    volume_rows = []
    for r in order_volume.collect():
        volume_rows.append((target_date, "order_count", json.dumps({"symbol": r.ticker_symbol}), float(r.order_count)))
        volume_rows.append((target_date, "total_notional", json.dumps({"symbol": r.ticker_symbol}), float(r.total_notional or 0)))

    # Combine + write
    all_rows = top_movers_rows + volume_rows
    if all_rows:
        out_df = spark.createDataFrame(all_rows, ["metric_date", "metric_name", "dimensions", "value"])
        # Convert metric_date to date and dimensions json string to JSONB through PG cast
        write_pg_table(out_df, jdbc_url, "analytics_daily", user, password, mode="append")
        print(f"Wrote {len(all_rows)} rows to analytics_daily for {target_date}")
    else:
        print(f"No data for {target_date} — skipping write.")

    spark.stop()


if __name__ == "__main__":
    main()
