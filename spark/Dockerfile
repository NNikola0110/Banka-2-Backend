FROM apache/spark:3.5.8-scala2.12-java17-python3-ubuntu

USER root

WORKDIR /opt/spark/jobs

# JDBC drivers
RUN apt-get update && apt-get install -y --no-install-recommends \
        wget python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && wget -O /opt/spark/jars/postgresql-42.7.4.jar \
        https://jdbc.postgresql.org/download/postgresql-42.7.4.jar

COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt

COPY jobs/ /opt/spark/jobs/

USER 185
