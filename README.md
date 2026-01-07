# Personalist

Personalist Encyclopedia

Read about it here: [*Personalist - An integrated universe of personal encyclopedias [Whitepaper]*](https://eighttrigrams.substack.com/p/personalist)

The app is publicly accessible under https://www.personalist.org.

## Quickstart and Demo

Run

```bash
$ make start
```

Now visit a browser under http://localhost:3017.

To stop or restart afterwards, use

```bash
$ make stop
$ make restart
```

## Configuration

On first run, `config.edn` is auto-created with defaults. Modify it to customize:

- `:shadow? true` - enable hot reload for ClojureScript development
- `:pre-seed? false` - disable demo data seeding
- `:db {:type :on-disk :path "data/xtdb"}` - use persistent storage

See `config.edn.template` for examples.

## Development

### Prerequisites

- Clojure
- Claude Code (optional)

### Running

Add `:shadow? true` to your `config.edn` for hot reload, then:

```bash
$ make start
```

This starts:

- the server on port 3017
- an nREPL server on port 7888
- shadow-cljs in watch mode (if `:shadow? true`)
- an in-memory database, pre-seeded with demo data (by default)

With `scripts/start.sh`, you can use `PORT=<port>` and `NREPL_PORT=<port>` for custom ports.

### nREPL

Connect to the running nREPL from your editor (Calva, CIDER, Cursive) on port 7888, or via command line:

```bash
$ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M -m nrepl.cmdline --connect --port 7888
```

### Tests

```bash
$ make test
```

### Test production build locally

```bash
$ make start-prod
```

This builds the uberjar and runs it locally.

## Deployment

```bash
$ make deploy
```

## Backup

```bash
$ make backup
```
