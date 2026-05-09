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

clean:
	rm -rf data
