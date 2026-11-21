FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 worker
COPY --from=build /workspace/target/*.jar /app/worker.jar
USER worker
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/worker.jar"]
