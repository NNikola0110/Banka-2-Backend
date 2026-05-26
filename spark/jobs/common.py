"""Shared utilities za sve Spark jobs."""
import os
import logging
from typing import Any
from pyspark.sql import SparkSession

logger = logging.getLogger(__name__)


def build_spark(app_name: str) -> SparkSession:
    """Kreira SparkSession sa default configuracijom."""
    return (
        SparkSession.builder
        .appName(app_name)
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.driver.extraClassPath", "/opt/spark/jars/postgresql-42.7.4.jar")
        .config("spark.executor.extraClassPath", "/opt/spark/jars/postgresql-42.7.4.jar")
        .getOrCreate()
    )


def get_env(key: str, default: str | None = None, required: bool = False) -> str:
    """Cita env var, fail-fast ako je required i missing."""
    val = os.getenv(key, default)
    if required and val is None:
        raise RuntimeError(f"Required env var {key} is missing")
    return val or ""


def read_pg_table(spark: SparkSession, jdbc_url: str, table: str, user: str, password: str):
    """Cita PostgreSQL tabelu kao Spark DataFrame."""
    return (
        spark.read
        .format("jdbc")
        .option("url", jdbc_url)
        .option("dbtable", table)
        .option("user", user)
        .option("password", password)
        .option("driver", "org.postgresql.Driver")
        .load()
    )


def write_pg_table(df, jdbc_url: str, table: str, user: str, password: str, mode: str = "append"):
    """Pise Spark DataFrame u PostgreSQL tabelu."""
    (df.write
        .format("jdbc")
        .option("url", jdbc_url)
        .option("dbtable", table)
        .option("user", user)
        .option("password", password)
        .option("driver", "org.postgresql.Driver")
        .mode(mode)
        .save())
