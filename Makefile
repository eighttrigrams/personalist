.PHONY: start start-dev start-demo stop restart restart-dev restart-demo seed test build deploy backup

start:
	./scripts/start.sh

start-dev:
	./scripts/start.sh dev

start-demo:
	./scripts/start.sh demo

stop:
	./scripts/stop.sh

restart:
	./scripts/restart.sh

restart-dev:
	./scripts/restart.sh dev

restart-demo:
	./scripts/restart.sh demo

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
