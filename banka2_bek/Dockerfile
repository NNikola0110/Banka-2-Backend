# syntax=docker/dockerfile:1.7
# Build kontekst je Banka-2-Backend/ (root aggregatora) jer monolit zavisi
# od banka2-contracts modula koji nije objavljen na remote repo-u.
#
# Java 25 LTS bumpovan 03.05.2026. BuildKit cache mount za .m2 — Maven dep
# fetch se ne ponavlja izmedju no-cache rebuild-ova ako pom.xml nije menjan.
# Rebuild time: ~120s (no cache) → ~25s (warm .m2 cache).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml .
COPY banka2-contracts/pom.xml banka2-contracts/
COPY banka2_bek/pom.xml banka2_bek/
COPY trading-service/pom.xml trading-service/
COPY notification-service/pom.xml notification-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl banka2_bek -am dependency:go-offline -DskipTests

COPY banka2-contracts/src banka2-contracts/src
COPY banka2_bek/src banka2_bek/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl banka2_bek -am package -DskipTests -q

# ---- Layer extraction stage ----
# Spring Boot layered jar: razdvaja fat jar u 4 layer-a (dependencies,
# spring-boot-loader, snapshot-dependencies, application). Docker tako
# kesh-ira deps zasebno od app code-a — promena 1 reda u kodu rebuild-uje
# samo `application` layer (~5MB), ne 80MB fat jar.
FROM eclipse-temurin:25-jre AS layers
WORKDIR /app
COPY --from=build /app/banka2_bek/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl je potreban samo za docker-compose `healthcheck:` koji proverava
# /v3/api-docs i tako sinhronizuje seed servis (ceka backend healthy).
# Bez ovog, healthcheck puca jer ni curl ni wget nisu prisutni u temurin:25-jre.
#
# Mirror fallback strategija (GHA Azure runner debugging 04.05.2026):
# 1. Probamo default mirror (archive.ubuntu.com:80)
# 2. Fallback na azure.archive.ubuntu.com — INTERNI Azure datacenter mirror,
#    ne pati od outbound port 80 firewall-a koji povremeno hvata GHA runner-e
#    ("Connection refused" ka archive.ubuntu.com:80 na svim IP-ovima)
# 3. Last-resort fallback na HTTPS varijantu archive.ubuntu.com:443
# Svaki pokusaj ima Acquire::Retries=5 (interno apt retry po paketu) plus
# 3-step bash retry sa exponential backoff za pocetni handshake.
RUN set -eux; \
    install_curl() { \
        for i in 1 2 3; do \
            if apt-get -o Acquire::Retries=5 update \
               && apt-get -o Acquire::Retries=5 install -y --no-install-recommends curl; then \
                return 0; \
            fi; \
            [ "$i" = "3" ] && return 1; \
            echo "apt-get attempt $i failed, retrying in $((i * 10))s..."; \
            sleep $((i * 10)); \
        done; \
    }; \
    switch_mirror() { \
        from="$1"; to="$2"; \
        if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list.d/ubuntu.sources; \
        fi; \
        if [ -f /etc/apt/sources.list ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list; \
        fi; \
    }; \
    if ! install_curl; then \
        echo "Default Ubuntu mirror failed — switching to Azure mirror"; \
        switch_mirror 'http://archive.ubuntu.com' 'http://azure.archive.ubuntu.com'; \
        switch_mirror 'http://security.ubuntu.com' 'http://azure.archive.ubuntu.com'; \
        if ! install_curl; then \
            echo "Azure mirror failed — switching to HTTPS upstream"; \
            switch_mirror 'http://azure.archive.ubuntu.com' 'https://archive.ubuntu.com'; \
            install_curl || { echo "All mirrors failed — apt-get cannot reach any Ubuntu repository" >&2; exit 1; }; \
        fi; \
    fi; \
    rm -rf /var/lib/apt/lists/*

# Kopiramo layer-e u tacno odredjenom redosledu — najmanje promenljiv prvo,
# najvise promenljiv (application) zadnji. Docker cesh-ira u poretku COPY
# linija, pa se rebuild dotice samo poslednje promenjenog layer-a.
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./

# Non-root user (uid 1001) za K8s PodSecurityStandard "restricted" compliance.
# Dual-toolchain fallback: groupadd/useradd (Debian/Ubuntu — eclipse-temurin
# base) ili addgroup/adduser (Alpine — busybox utils). 2>/dev/null suppress-uje
# stderr ako prvi alat nije prisutan, `||` prebacuje na drugi. chown -R /app
# obezbedjuje da non-root user moze da cita layered jar bez "Permission denied".
RUN groupadd -g 1001 -r spring 2>/dev/null || addgroup -g 1001 spring \
 && useradd -u 1001 -g 1001 -r -m -s /bin/sh spring 2>/dev/null || adduser -u 1001 -G spring -D -s /bin/sh spring \
 && chown -R 1001:1001 /app
USER 1001

EXPOSE 8080
# `JarLauncher` je Spring Boot bootstrapper koji ucitava layered jar.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
