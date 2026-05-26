"""
Fraud Detection — KMeans clustering nad transakcijama, distance-to-centroid kao risk score.
Reads banka-core db transactions (60 dana), engineers features, clusters, scores.

Cron: 03:00 daily.
Output: banka_core.transaction_anomalies sa risk_score > 0.8 → notify Discord preko alert-router.
"""
import sys
from datetime import datetime, timezone, timedelta
from pyspark.sql import functions as F, Window
from pyspark.ml.feature import VectorAssembler, StandardScaler
from pyspark.ml.clustering import KMeans
from pyspark.sql.types import DoubleType
from common import build_spark, get_env, read_pg_table, write_pg_table


def main():
    spark = build_spark("FraudDetection")

    # Banka-core baza (read replica)
    banka_jdbc = get_env("BANKA_CORE_DB_JDBC_URL", required=True)
    banka_user = get_env("BANKA_CORE_READER_USER", default="banka2user")
    banka_password = get_env("BANKA_CORE_READER_PASSWORD", required=True)

    cutoff = datetime.now(timezone.utc) - timedelta(days=60)
    # TODO: schema assumption — transactions ima (id, from_account_id, amount, created_at).
    # Stvarna shema moze imati account_from_id / account_to_id — verify pre prvog run-a.
    tx_df = read_pg_table(spark, banka_jdbc, "transactions", banka_user, banka_password)
    tx_df = tx_df.filter(F.col("created_at") >= F.lit(cutoff))

    if tx_df.count() < 100:
        print("Premalo transakcija za fraud detection — skipping.")
        spark.stop()
        return

    # Feature engineering
    w = Window.partitionBy("from_account_id").orderBy("created_at")
    tx_df = tx_df.withColumn("amount_log", F.log(F.col("amount") + 1))
    tx_df = tx_df.withColumn("hour", F.hour("created_at").cast(DoubleType()))
    tx_df = tx_df.withColumn("day_of_week", F.dayofweek("created_at").cast(DoubleType()))
    tx_df = tx_df.withColumn("avg_amount_30d", F.avg("amount").over(w.rowsBetween(-30, -1)))
    tx_df = tx_df.withColumn("z_score", (F.col("amount") - F.col("avg_amount_30d")) / F.stddev("amount").over(w.rowsBetween(-30, -1)))
    tx_df = tx_df.na.fill({"avg_amount_30d": 0.0, "z_score": 0.0})

    assembler = VectorAssembler(
        inputCols=["amount_log", "hour", "day_of_week", "z_score"],
        outputCol="raw_features"
    )
    vec_df = assembler.transform(tx_df)
    scaler = StandardScaler(inputCol="raw_features", outputCol="features")
    scaler_model = scaler.fit(vec_df)
    scaled_df = scaler_model.transform(vec_df)

    # KMeans k=5 — anomalije su tacke daleko od svih centroidova
    kmeans = KMeans(k=5, seed=42)
    model = kmeans.fit(scaled_df)
    centers = model.clusterCenters()

    def min_distance_udf(features):
        import numpy as np
        f = np.array(features.toArray())
        return float(min(np.linalg.norm(f - np.array(c)) for c in centers))

    from pyspark.sql.functions import udf
    min_dist = udf(min_distance_udf, DoubleType())
    scored = scaled_df.withColumn("min_dist", min_dist(F.col("features")))

    # Normalizuj na 0..1 (percentile-based — top 5% imaju score > 0.95)
    max_dist = scored.agg(F.max("min_dist")).first()[0] or 1.0
    scored = scored.withColumn("risk_score", F.least(F.lit(1.0), F.col("min_dist") / F.lit(max_dist)))

    # Filter ako risk > 0.8
    high_risk = scored.filter(F.col("risk_score") > 0.8).select(
        F.col("id").alias("transaction_id"),
        F.col("risk_score"),
        F.to_json(F.struct("amount", "hour", "day_of_week", "z_score")).alias("features"),
        F.lit("kmeans_v1").alias("model_version")
    )

    count_high = high_risk.count()
    if count_high > 0:
        write_pg_table(high_risk, banka_jdbc, "transaction_anomalies", banka_user, banka_password, mode="append")
        print(f"Wrote {count_high} fraud alerts.")
    else:
        print("No high-risk transactions detected.")

    spark.stop()


if __name__ == "__main__":
    main()
