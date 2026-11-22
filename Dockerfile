# The runtime image is Alpine. The build stage stays on the full JDK so the
# Gradle wrapper has a normal glibc toolchain while it resolves dependencies.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
# Sized for a 512 MB machine. 60% of the cgroup leaves room for metaspace,
# code cache, and native memory. Serial GC avoids G1's extra structures on a
# small heap. 512k stacks and C1-only JIT keep the footprint down. Exit on
# OOM so the platform restarts the process instead of leaving it wedged.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=60", \
  "-XX:+UseSerialGC", \
  "-Xss512k", \
  "-XX:TieredStopAtLevel=1", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
