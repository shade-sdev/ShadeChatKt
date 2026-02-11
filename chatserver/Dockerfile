# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

RUN chmod +x gradlew && sed -i 's/\r$//' gradlew

COPY src src
RUN ./gradlew installDist

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/build/install/chatserver ./

# Optimization Flags:
# 1. UseContainerSupport: Ensures JVM respects Docker memory limits
# 2. MaxRAMPercentage: Dynamically sets heap size (75% of container RAM)
# 3. Xshare:on: Speeds up startup using Class Data Sharing
# 4. ZGC: Modern, low-latency garbage collector (great for WebSockets/Chat)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseZGC \
               -XX:+ZGenerational \
               -Xshare:on \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# We use the shell form here to ensure JAVA_OPTS are expanded
ENTRYPOINT ["sh", "-c", "bin/chatserver $JAVA_OPTS"]