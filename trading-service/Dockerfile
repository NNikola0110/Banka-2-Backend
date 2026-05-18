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
    mvn -B -pl trading-service -am dependency:go-offline -DskipTests
COPY banka2-contracts/src banka2-contracts/src
COPY trading-service/src trading-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl trading-service -am package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/trading-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
