.PHONY: start start-prod stop restart restart-prod seed test build deploy backup

start:
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
	fly ssh console -C "tar -czf - /app/data" > volume-backup.tar.gz
