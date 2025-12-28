# Personalist

Personalist Encyclopedia

The current implementation is completely out of date and will be re-written entirely in due course.

For a whitepaper, see here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

---

Start with `$ PORT=3017 clojure -X:xtdb`.

---

curl -X POST http://localhost:3017/graphql \
   -H "Content-Type: application/json" \
   -d '{"query": "{ identitiesByPersonId(personName: \"dan\") { identity text }}"}' 

curl -X POST http://localhost:3017?query="{ identitiesByPersonId(personName: \"dan\") { identity text }}"

---
