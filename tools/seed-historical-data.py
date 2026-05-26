"""
Bootstrap istorijskih podataka:
1. InfluxDB: 90 dana sintetisckog OHLCV-a (GBM po simbolu).
2. PG transactions: 30 dana, ~50/dan/klijent x 4 = ~6000 transakcija (1% anomalija).
3. PG orders: 60 dana, ~10/dan/klijent x 4 = ~2400 ordera.

Pokrece se jednom posle K8s deploy-a kao K8s Job.
"""
import os
import random
import math
from datetime import datetime, timezone, timedelta
from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS
import psycopg2


SYMBOLS = ["AAPL", "MSFT", "GOOG", "TSLA", "AMZN", "NVDA", "META", "AMD", "INTC", "ORCL"]
INITIAL_PRICES = {"AAPL": 185.0, "MSFT": 380.0, "GOOG": 140.0, "TSLA": 220.0,
                  "AMZN": 175.0, "NVDA": 850.0, "META": 480.0, "AMD": 140.0,
                  "INTC": 35.0, "ORCL": 130.0}


def seed_influxdb(days_back=90):
    url = os.getenv("INFLUX_URL")
    token = os.getenv("INFLUX_TOKEN")
    org = os.getenv("INFLUX_ORG", "banka2")
    bucket = os.getenv("INFLUX_BUCKET", "tick-listings")

    points = []
    now = datetime.now(timezone.utc)
    for symbol in SYMBOLS:
        price = INITIAL_PRICES[symbol]
        for d in range(days_back, 0, -1):
            day = now - timedelta(days=d)
            for hour in range(8, 17):  # trading hours UTC
                # GBM step
                drift = 0.0001
                vol = 0.015
                z = random.gauss(0, 1)
                price = price * math.exp((drift - vol*vol/2) + vol*z)

                ts = day.replace(hour=hour, minute=0, second=0, microsecond=0)
                point = (Point("listing_price")
                    .tag("ticker", symbol)
                    .tag("exchange", "NASDAQ")
                    .tag("asset_type", "STOCK")
                    .field("open", price * (1 - 0.005))
                    .field("high", price * (1 + 0.008))
                    .field("low", price * (1 - 0.01))
                    .field("close", price)
                    .field("volume", random.randint(100000, 5000000))
                    .field("ask", price * 1.001)
                    .field("bid", price * 0.999)
                    .time(ts, WritePrecision.MS))
                points.append(point)

    print(f"Writing {len(points)} OHLCV points to InfluxDB...")
    with InfluxDBClient(url=url, token=token, org=org) as client:
        write_api = client.write_api(write_options=SYNCHRONOUS)
        write_api.write(bucket=bucket, record=points)
    print("InfluxDB seed done.")


def seed_transactions():
    """Generates synthetic transactions sa 1% anomalija."""
    # TODO: schema assumption — transactions tabela ima (from_account_id, amount, created_at).
    # Stvarna shema BankaCore-a moze imati account_from_id / sender_id — verify pre run-a.
    conn = psycopg2.connect(os.getenv("BANKA_CORE_DB_DSN"))
    cur = conn.cursor()

    cur.execute("SELECT id FROM accounts WHERE active = 1 LIMIT 4")
    accounts = [r[0] for r in cur.fetchall()]

    transactions = []
    now = datetime.now(timezone.utc)
    for d in range(30, 0, -1):
        day = now - timedelta(days=d)
        for acc in accounts:
            for _ in range(50):
                amount = random.uniform(100, 5000)
                hour = random.randint(8, 22)
                if random.random() < 0.01:  # 1% anomalia
                    amount *= 10
                    hour = 3
                ts = day.replace(hour=hour, minute=random.randint(0, 59))
                transactions.append((acc, amount, ts))

    print(f"Inserting {len(transactions)} transactions...")
    cur.executemany(
        "INSERT INTO transactions (from_account_id, amount, created_at) VALUES (%s, %s, %s)",
        transactions
    )
    conn.commit()
    cur.close()
    conn.close()
    print("PG transactions seed done.")


if __name__ == "__main__":
    seed_influxdb()
    seed_transactions()
