DB_URL ?= postgres://schism:schism@127.0.0.1:55432/schism?sslmode=disable

# Start Postgres only (for local `go test` / `go run`).
db:
	docker compose up -d db

# Build and run the whole stack (Postgres + Go server) in Docker.
up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f server

test:
	DATABASE_URL="$(DB_URL)" go test ./...

run:
	DATABASE_URL="$(DB_URL)" ADDR=":8080" go run ./cmd/server

# Wipe group data and insert mock groups/people/expenses for a clean demo restart.
seed:
	DATABASE_URL="$(DB_URL)" go run ./cmd/seed

.PHONY: db up down logs test run seed
