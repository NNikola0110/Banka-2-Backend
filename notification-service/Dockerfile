# syntax=docker/dockerfile:1.7
# Build kontekst je Banka-2-Backend/ (root aggregatora) jer modul zavisi
# od banka2-contracts modula koji nije objavljen na remote repo-u.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY banka2-contracts/pom.xml banka2-contracts/
COPY banka2_bek/pom.xml banka2_bek/
COPY trading-service/pom.xml trading-service/
COPY notification-service/pom.xml notification-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl notification-service -am dependency:go-offline -DskipTests
COPY banka2-contracts/src banka2-contracts/src
COPY notification-service/src notification-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl notification-service -am package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/notification-service/target/*.jar app.jar

# Non-root user (uid 1001) za K8s PodSecurityStandard "restricted" compliance.
# Dual-toolchain fallback: groupadd/useradd (Debian/Ubuntu — eclipse-temurin
# base) ili addgroup/adduser (Alpine — busybox utils). 2>/dev/null suppress-uje
# stderr ako prvi alat nije prisutan, `||` prebacuje na drugi. chown -R /app
# obezbedjuje da non-root user moze da cita app.jar bez "Permission denied".
RUN groupadd -g 1001 -r spring 2>/dev/null || addgroup -g 1001 spring \
 && useradd -u 1001 -g 1001 -r -m -s /bin/sh spring 2>/dev/null || adduser -u 1001 -G spring -D -s /bin/sh spring \
 && chown -R 1001:1001 /app
USER 1001

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
