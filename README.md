# Personalist

Personalist Encyclopedia

Read about it here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

The app is publicly accessible under https://personalist.fly.dev.

## Quickstart and Demo

Run

```bash
$ make start-demo
```

Now visit a browser under http://localhost:3017.

To stop or restart afterwards, use

```bash
$ make stop-demo
$ make restart-demo
```

## Use a persistent database

Modify `config.edn` accordingly (see `config.edn.template`).

## Development

### Prerequisites

- Clojure
- Claude Code (optional)

### Running

```bash
$ make start-dev
$ make stop (afterwards)
```

This starts 

- the server on port 3017
- an REPL server on port 7888.
- shadow-cljs (hot code reload etc.)
- use an in-memory database, which is pre-seeded (unless a `config.edn` is provided; see above)

With `scripts/start.sh`, you can use (prefix the command with) `PORT=<3017>` and `NREPL_PORT=<7999>` for custom ports.

Use

```bash
$ make restart-dev
```

to restart the application.

### nREPL

Connect to the running nREPL from your editor (Calva, CIDER, Cursive) on port 7888, or via command line:

```bash
$ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M -m nrepl.cmdline --connect --port 7888
```

### Tests

```bash
$ make test
```

## Deployment

```bash
$ make deploy
```

## Backup

```bash
$ make backup
```
