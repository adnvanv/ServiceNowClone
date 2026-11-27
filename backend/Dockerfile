# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Run as an unprivileged user (matches runAsUser in the Helm chart).
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
