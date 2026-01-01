# Personalist

Personalist Encyclopedia

Read about it here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

## Quickstart and Demo

Run

```bash
$ scripts/start.sh
# Visit a browser under http://localhost:3017
$ scripts/stop.sh (afterwards)
```

## Development

### Prerequisites

- Clojure
- Claude Code (optional)

### Running

```bash
$ scripts/start.sh dev
$ scripts/stop.sh (afterwards)
```

This starts 

- the server on port 3017
- an REPL server on port 7888.
- shadow-cljs (hot code reload etc.)

With `start.sh`, you can use (prefix the command with) `PORT=<3017>` and `NREPL_PORT=<7999>` for custom ports.

Use

```bash
$ scripts/stop.sh
```

to tear things down afterwards.

### nREPL

Connect to the running nREPL:

```bash
$ basedir-eval -p 7888 "(+ 1 2)"
```

### Tests

```bash
$ clj -M:test
```

### GraphQL

```bash
$ curl -X POST http://localhost:3017/graphql \
   -H "Content-Type: application/json" \
   -d '{"query": "{ identitiesByPersonaId(personaName: \"dan\") { identity text }}"}'
```
