FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk fonts-wqy-microhei fontconfig \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/interviewmirror-backend-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
