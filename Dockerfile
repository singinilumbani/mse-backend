# ---- Stage 1: build the server ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew buildFatJar --no-daemon

# ---- Stage 2: a small image that only runs it ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*-all.jar /app/server.jar

# The database lives in /tmp. A free host wipes it whenever the server restarts.
ENV MSE_DB=/tmp/mse.db

# Keep the memory use small: the free plan has 512 MB
CMD ["java", "-Xmx300m", "-jar", "/app/server.jar"]