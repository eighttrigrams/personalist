.PHONY: start start-prod stop restart restart-prod seed test build deploy backup swap-config-bkp

start:
	@test -d node_modules || npm i
	./scripts/start.sh

start-prod:
	./scripts/start.sh prod

stop:
	./scripts/stop.sh

restart:
	./scripts/restart.sh

restart-prod:
	./scripts/restart.sh prod

seed:
	./scripts/seed-db.sh

test:
	clj -M:test

build:
	clj -T:build uber

deploy:
	fly deploy

backup:
	fly ssh console -C "tar -czf - /app/data/xtdb" > volume-backup.tar.gz

swap-config-bkp:
	@mv config.edn config.edn.tmp && mv config.edn.bkp config.edn && mv config.edn.tmp config.edn.bkp
	@echo "Swapped config.edn <-> config.edn.bkp"
