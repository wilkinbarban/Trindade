.PHONY: install dev build db-reset docker-up docker-down

install:
	npm install

dev:
	npm run dev

build:
	npm run build

db-reset:
	rm -f packages/backend/data/trindade.db
	@echo "Database reset. Restart the backend to recreate."

docker-up:
	docker compose up -d

docker-down:
	docker compose down
