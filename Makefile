.PHONY: start start-dev stop restart restart-dev seed test deploy backup

start:
	./scripts/start.sh

start-dev:
	./scripts/start.sh dev

stop:
	./scripts/stop.sh

restart:
	./scripts/restart.sh

restart-dev:
	./scripts/restart.sh dev

seed:
	./scripts/seed-db.sh

test:
	clj -M:test

deploy:
	fly deploy

backup:
	fly ssh console -C "tar -czf - /app/data" > volume-backup.tar.gz
