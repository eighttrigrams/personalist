# Personalist

Personalist Encyclopedia

## Running

```bash
$ PORT=3017 clojure -X:xtdb
```

This starts the server on port 3017 and an nREPL server on port 7888.

To customize the nREPL port:

```bash
$ PORT=3017 NREPL_PORT=7999 clojure -X:xtdb
```

## nREPL

Connect to the running nREPL:

```bash
$ basedir-eval -p 7888 "(+ 1 2)"
```

## Tests

```bash
$ clj -M:test
```

## GraphQL

```bash
$ curl -X POST http://localhost:3017/graphql \
   -H "Content-Type: application/json" \
   -d '{"query": "{ identitiesByPersonId(personName: \"dan\") { identity text }}"}'
```
