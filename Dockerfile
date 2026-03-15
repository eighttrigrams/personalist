FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

RUN apk add --no-cache nodejs npm

COPY package.json package-lock.json ./
RUN npm install

COPY shadow-cljs.edn ./
COPY deps.edn build.clj ./
COPY src ./src
COPY resources ./resources
COPY config.prod.edn ./config.edn

RUN npx shadow-cljs release app

RUN clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /opt/target/personalist-0.0.1-standalone.jar /app/app.jar
COPY --from=builder /opt/config.edn /app/config.edn

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Xms256m", \
            "-Xmx768m", \
            "-XX:MaxMetaspaceSize=256m", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "-jar", "/app/app.jar"]
