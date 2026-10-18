# Built by Docker Compose with the backend folder as context:
#   docker compose build ingest-api
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ingest-api/pom.xml ingest-api/
COPY status-service/pom.xml status-service/
COPY ingest-api/src ingest-api/src
# The cache mount keeps downloaded Maven dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ingest-api -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ingest-api/target/ingest-api.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
