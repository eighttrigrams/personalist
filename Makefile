.PHONY: start start-prod stop restart restart-prod seed test build deploy backup swap-config-bkp

start:
	@test -d node_modules || npm i
	./scripts/start.sh

start-prod:
	./scripts/start.sh prod

stop:
	./scripts/stop.sh

seed:
	./scripts/seed-db.sh

test:
	clj -M:test

build:
	clj -T:build uber

deploy:
	fly deploy

backup:
	fly ssh console -C "tar -czf - /app/data/xtdb" > volume-backup.$$(date +%Y-%m-%d.%H-%M).tar.gz

backup-replay:
	@if [ -d data ]; then echo "Error: data/ directory already exists. Remove it first." && exit 1; fi
	tar -xzf $$(ls -t volume-backup.*.tar.gz | head -1) --strip-components=1
