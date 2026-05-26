"""
Price Prediction — RandomForestRegressor MLlib za N+1 day close predikciju.
Reads InfluxDB OHLCV history (90 dana), engineers features (lags, MA, vol),
trenira po simbolu, predicira sutrasnji close + interval.

Cron: 02:00 daily.
Output: trading_db.price_predictions.
"""
import sys
from datetime import datetime, timezone, timedelta
from pyspark.sql import functions as F, Window
from pyspark.ml.feature import VectorAssembler
from pyspark.ml.regression import RandomForestRegressor
from influxdb_client import InfluxDBClient
from common import build_spark, get_env, write_pg_table


def fetch_influx_ohlcv(spark, symbols, days_back=90):
    """Vrati Spark DataFrame OHLCV za sve simbole."""
    url = get_env("INFLUX_URL", required=True)
    token = get_env("INFLUX_TOKEN", required=True)
    org = get_env("INFLUX_ORG", default="banka2")
    bucket = get_env("INFLUX_BUCKET", default="tick-listings")

    rows = []
    with InfluxDBClient(url=url, token=token, org=org) as client:
        query_api = client.query_api()
        for symbol in symbols:
            flux = f'''
                from(bucket: "{bucket}")
                  |> range(start: -{days_back}d)
                  |> filter(fn: (r) => r._measurement == "listing_price" and r.ticker == "{symbol}")
                  |> filter(fn: (r) => r._field == "close")
                  |> aggregateWindow(every: 1d, fn: last, createEmpty: false)
                  |> yield(name: "daily_close")
            '''
            tables = query_api.query(flux)
            for table in tables:
                for record in table.records:
                    rows.append((symbol, record.get_time().date(), float(record.get_value())))

    if not rows:
        return None
    return spark.createDataFrame(rows, ["symbol", "date", "close"])


def main():
    spark = build_spark("PricePrediction")
    target_date = (datetime.now(timezone.utc) + timedelta(days=1)).date()

    jdbc_url = get_env("TRADING_DB_JDBC_URL", required=True)
    user = get_env("ANALYTICS_READER_USER", default="analytics_reader")
    password = get_env("ANALYTICS_READER_PASSWORD", required=True)

    # Hardcoded simboli za pilot — kasnije fetch from listings table
    symbols = ["AAPL", "MSFT", "GOOG", "TSLA", "AMZN", "NVDA"]

    df = fetch_influx_ohlcv(spark, symbols)
    if df is None or df.count() == 0:
        print("No OHLCV data — skipping prediction.")
        spark.stop()
        return

    # Feature engineering: lag-1, lag-7, MA-5, MA-20, vol-20
    w = Window.partitionBy("symbol").orderBy("date")
    df = df.withColumn("lag1", F.lag("close", 1).over(w))
    df = df.withColumn("lag7", F.lag("close", 7).over(w))
    df = df.withColumn("ma5", F.avg("close").over(w.rowsBetween(-4, 0)))
    df = df.withColumn("ma20", F.avg("close").over(w.rowsBetween(-19, 0)))
    df = df.withColumn("vol20", F.stddev("close").over(w.rowsBetween(-19, 0)))

    df = df.na.drop()

    # Per-symbol training (simple loop — production bi bilo paralelno preko cross-validator)
    predictions = []
    for symbol in symbols:
        sym_df = df.filter(F.col("symbol") == symbol)
        if sym_df.count() < 30:
            print(f"Not enough data for {symbol} — skipping.")
            continue

        # Train: koristi sve sem zadnje tacke
        train_df = sym_df.orderBy("date").limit(sym_df.count() - 1)

        assembler = VectorAssembler(
            inputCols=["lag1", "lag7", "ma5", "ma20", "vol20"],
            outputCol="features"
        )
        train_vec = assembler.transform(train_df).select("features", F.col("close").alias("label"))

        rf = RandomForestRegressor(numTrees=100, maxDepth=8, seed=42)
        model = rf.fit(train_vec)

        # Predict za sutra (koristi zadnju tacku kao input feature)
        latest = sym_df.orderBy(F.col("date").desc()).limit(1)
        latest_vec = assembler.transform(latest)
        pred_row = model.transform(latest_vec).collect()[0]
        predicted_close = float(pred_row.prediction)

        # Confidence interval: ±2 × stdev iz residuals
        residuals_df = model.transform(train_vec)
        residual_stddev = residuals_df.select(F.stddev(F.col("prediction") - F.col("label"))).first()[0] or 0.0
        margin = 2 * residual_stddev

        predictions.append((symbol, target_date, predicted_close, predicted_close - margin, predicted_close + margin, "rf_v1"))

    if predictions:
        pred_df = spark.createDataFrame(
            predictions,
            ["symbol", "prediction_date", "predicted_close", "lower_bound", "upper_bound", "model_version"]
        )
        write_pg_table(pred_df, jdbc_url, "price_predictions", user, password, mode="append")
        print(f"Wrote {len(predictions)} predictions for {target_date}")

    spark.stop()


if __name__ == "__main__":
    main()
