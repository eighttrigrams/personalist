FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

RUN apk add --no-cache nodejs npm

COPY package.json package-lock.json ./
RUN npm install

COPY shadow-cljs.edn ./
COPY deps.edn build.clj ./
COPY src ./src
COPY resources ./resources
COPY config.prod.edn ./

RUN npx shadow-cljs release app

RUN clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /opt/target/personalist-0.0.1-standalone.jar /app/app.jar
COPY --from=builder /opt/config.prod.edn /app/config.edn

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Xms256m", \
            "-Xmx768m", \
            "-XX:MaxMetaspaceSize=128m", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "-XX:TieredStopAtLevel=1", \
            "--add-opens=java.base/java.nio=ALL-UNNAMED", \
            "-Dio.netty.tryReflectionSetAccessible=true", \
            "--enable-native-access=ALL-UNNAMED", \
            "-Darrow.enable_unsafe_memory_access=false", \
            "-Darrow.enable_null_check_for_get=true", \
            "-jar", "/app/app.jar"]
