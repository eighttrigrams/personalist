# Personalist

Personalist Encyclopedia

The current implementation is completely out of date and will be re-written entirely in due course.

For a whitepaper, see here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

## Running

Start with `$ PORT=3017 clojure -X:xtdb`.

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
   -d '{"query": "{ identitiesByPersonaId(personaName: \"dan\") { identity text }}"}'
```
