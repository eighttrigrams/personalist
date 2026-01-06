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

fly-deploy:
	fly deploy

fly-backup:
	fly ssh console -C "tar -czf - /app/data/xtdb" > volume-backup.$$(date +%Y-%m-%d.%H-%M).tar.gz

fly-backup-replay:
	@if [ -d data ]; then echo "Error: data/ directory already exists. Remove it first." && exit 1; fi
	tar -xzf $$(ls -t volume-backup.*.tar.gz | head -1) --strip-components=1

railway-deploy:
	git push && railway up

fly-railway-replay:
	@echo "Restoring Fly.io backup to Railway volume..."
	@if [ ! -f $$(ls -t volume-backup.*.tar.gz 2>/dev/null | head -1) ]; then \
		echo "Error: No backup file found. Run 'make fly-backup' first."; \
		exit 1; \
	fi
	@echo "Extracting backup to temporary directory..."
	@mkdir -p tmp-backup
	@tar -xzf $$(ls -t volume-backup.*.tar.gz | head -1) -C tmp-backup
	@echo "Uploading to Railway volume..."
	@railway run -- bash -c "rm -rf /app/data/xtdb/* && mkdir -p /app/data/xtdb"
	@cd tmp-backup/app/data/xtdb && tar -czf - . | railway run -- bash -c "cd /app/data/xtdb && tar -xzf -"
	@rm -rf tmp-backup
	@echo "Backup restored successfully. Restart the Railway service to load the data."
