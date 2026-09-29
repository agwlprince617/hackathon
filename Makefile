# Launch Day starter kit
#   make up          start the stack
#   make check       run checks (TRACK=backend|platform|sdet|mobile|ai|smoke)
#   make authority MODE=down

COMPOSE ?= podman compose
TRACK   ?= smoke
MODE    ?= healthy
SDET_LANG ?= go
MOBILE_PLATFORM ?= flutter
KIT_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

.PHONY: up down logs ps build check authority demo tidy smoke reset verify

up:
	@chmod +x scripts/up.sh scripts/down.sh
	COMPOSE="$(COMPOSE)" ./scripts/up.sh

down:
	@chmod +x scripts/down.sh
	COMPOSE="$(COMPOSE)" ./scripts/down.sh

logs:
	$(COMPOSE) logs -f --tail=100

ps:
	$(COMPOSE) ps

build:
	mvn -q -f backend/java/pom.xml -DskipTests package
	$(COMPOSE) build reservation-api

authority:
	curl -sS -X POST http://127.0.0.1:9000/admin/mode \
		-H 'Content-Type: application/json' \
		-d '{"mode":"$(MODE)"}'
	@echo

reset:
	curl -sS -X POST http://127.0.0.1:9000/admin/reset -H 'Content-Type: application/json' -d '{}'
	curl -sS -X POST http://127.0.0.1:8080/admin/reset -H 'Content-Type: application/json' -d '{}'
	curl -sS -X POST http://127.0.0.1:8081/admin/reset -H 'Content-Type: application/json' -d '{}'
	curl -sS -X POST http://127.0.0.1:8082/admin/reset -H 'Content-Type: application/json' -d '{}'
	@echo

check:
	cd $(KIT_ROOT)/checks && \
	TRACK=$(TRACK) \
	API_URL=$${API_URL:-http://127.0.0.1:8080} \
	AUTHORITY_URL=$${AUTHORITY_URL:-http://127.0.0.1:9000} \
	REFERENCE_URL=$${REFERENCE_URL:-http://127.0.0.1:8081} \
	BUGGY_URL=$${BUGGY_URL:-http://127.0.0.1:8082} \
	KIT_ROOT=$(KIT_ROOT) \
	SDET_LANG=$(SDET_LANG) \
	MOBILE_PLATFORM=$(MOBILE_PLATFORM) \
	COMPOSE="$(COMPOSE)" \
	KILL_SERVICE=$${KILL_SERVICE:-reservation-api} \
	go run .

demo:
	API_URL=$${API_URL:-http://127.0.0.1:8081} bash platform/chaos/outage-demo.sh || \
		(echo "write platform/chaos/outage-demo.sh (see Platform brief)"; exit 1)

tidy:
	mvn -q -f backend/java/pom.xml verify

verify:
	$(MAKE) up
	RUN_LIVE_TESTS=true \
	API_URL=$${API_URL:-http://127.0.0.1:8080} \
	AUTHORITY_URL=$${AUTHORITY_URL:-http://127.0.0.1:9000} \
	mvn -q -f backend/java/pom.xml clean verify

smoke:
	$(MAKE) check TRACK=smoke
