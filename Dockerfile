# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# run as an unprivileged user, not root
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /src/target/*.jar app.jar
USER app

EXPOSE 8080
# size the heap from the container's memory limit instead of the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
