# Personalist

Personalist Encyclopedia

Read about it here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

The app is publicly accessible under https://personalist.fly.dev.

## Quickstart and Demo

Run

```bash
$ scripts/start.sh
```

Now visit a browser under http://localhost:3017.

To stop afterwards, use

```bash
$ scripts/stop.sh
```

## Use a persistent database

Put `config.edn` in place. This will allow you to choose to use a persistent database, or the in memory variant,
and let's you choose whether whatever db you choose should be pre-seeded with some data.

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
- use an in-memory database, which is pre-seeded (unless a `config.edn` is provided; see above)

With `start.sh`, you can use (prefix the command with) `PORT=<3017>` and `NREPL_PORT=<7999>` for custom ports.

Use

```bash
$ scripts/stop.sh
```

to tear things down afterwards.

### nREPL

Connect to the running nREPL from your editor (Calva, CIDER, Cursive) on port 7888, or via command line:

```bash
$ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M -m nrepl.cmdline --connect --port 7888
```

### Tests

```bash
$ clj -M:test
```

## Deployment

```bash
$ fly deploy
```

## Backup

```bash
$ fly ssh console -C "tar -czf - /app/data" > volume-backup.tar.gz
```
