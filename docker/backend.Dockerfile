# Build stage. The Gradle cache is mounted rather than baked into a layer, so dependency downloads
# survive between builds without bloating the image.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the build definition first: as long as it is unchanged, the dependency layer is reused even
# when every source file has changed.
COPY backend/gradle gradle
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/engine/build.gradle.kts engine/
COPY backend/modules/interpersonal/build.gradle.kts modules/interpersonal/
COPY backend/app/build.gradle.kts app/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY backend/engine engine
COPY backend/modules modules
COPY backend/app app

# Tests need a Docker daemon for Testcontainers, which an image build does not have. They run in CI
# and locally via ./gradlew build; the image build only has to produce the artefact.
RUN ./gradlew --no-daemon :app:bootJar -x test

# Split the fat jar so that dependencies, which rarely change, land in their own layer ahead of the
# application classes, which change on every commit.
#
# The extracted jar is named after the module and its version. Renaming it to a fixed name keeps
# the version number out of the runtime stage's entrypoint.
RUN java -Djarmode=tools -jar app/build/libs/app-*.jar extract --layers --destination extracted \
    && mv extracted/application/app-*.jar extracted/application/app.jar

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /application

# curl is here for the container health check, which compose gates the frontend on. The JRE image
# ships no HTTP client at all, so without this the check can never pass and the stack never starts.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root: a container that only serves HTTP has no business owning its own filesystem.
RUN groupadd --system fsp && useradd --system --gid fsp --home /application fsp
USER fsp

COPY --from=build --chown=fsp:fsp /workspace/extracted/dependencies/ ./
COPY --from=build --chown=fsp:fsp /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=fsp:fsp /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=fsp:fsp /workspace/extracted/application/ ./

EXPOSE 8080

# MaxRAMPercentage rather than a fixed heap: the container may be given more or less memory
# depending on the machine, and a hard -Xmx would either waste it or cause an OOM kill.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
