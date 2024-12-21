# Personalist

Personalist Encyclopedia

---

curl -X POST http://localhost:3017/graphql \
   -H "Content-Type: application/json" \
   -d '{"query": "{ identitiesByPersonId(personName: \"dan\") { identity text }}"}' 

curl -X POST http://localhost:3017?query="{ identitiesByPersonId(personName: \"dan\") { identity text }}"

Procfile
web: java --add-opens=java.base/java.nio=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -cp server.jar clojure.main -m et.pe.server