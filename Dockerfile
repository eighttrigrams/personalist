FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

COPY deps.edn build.clj ./
COPY src ./src
COPY resources ./resources

RUN clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /opt/target/personalist-0.0.1-standalone.jar /app/app.jar
COPY config.prod.edn /app/config.edn

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Xms256m", \
            "-Xmx768m", \
            "-XX:MaxMetaspaceSize=128m", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "--add-opens=java.base/java.nio=ALL-UNNAMED", \
            "-Dio.netty.tryReflectionSetAccessible=true", \
            "--enable-native-access=ALL-UNNAMED", \
            "-jar", "/app/app.jar"]
