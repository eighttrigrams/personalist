# Personalist

Personalist Encyclopedia

Start with `$ PORT=3017 clojure -X:xtdb`.

---

curl -X POST http://localhost:3017/graphql \
   -H "Content-Type: application/json" \
   -d '{"query": "{ identitiesByPersonId(personName: \"dan\") { identity text }}"}' 

curl -X POST http://localhost:3017?query="{ identitiesByPersonId(personName: \"dan\") { identity text }}"

---