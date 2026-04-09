# EAIS Game Night — Top-Level Makefile
# Orchestrates battle-cab server + Phaser spectator client
#
# ┌─────────────────────────────────────────────────────────┐
# │  COMPETITORS: Your 3 commands are:                      │
# │    1. make setup              (one-time)                │
# │    2. make lobby              (gear room + live preview) │
# │    3. make deploy             (send bot to arena)       │
# └─────────────────────────────────────────────────────────┘

# ============================================================
# GAME SERVER URL — set this before the event
# ============================================================
# For local dev, use http://localhost:33333
# For game night, this will be the remote server URL
GAME_SERVER ?= http://localhost:33333

.PHONY: setup lobby deploy status help run-mvp1 run-mvp2 install-spectator reset-game add-bot add-bots bot-move smart-bot lobby-install starter-bot

# ============================================================
# Competitor Flow
# ============================================================

# One-time setup: creates venv, installs deps, copies starter bot
# Usage: make setup NAME=my-bot
setup:
	$(eval NAME ?= my-bot)
	@echo "=== Setting up your bot: $(NAME) ==="
	@echo ""
	@# Create venv if needed
	@test -d lobby/.venv || (echo "Creating Python venv..." && python3 -m venv lobby/.venv)
	@lobby/.venv/bin/pip install -q -r lobby/requirements.txt
	@echo "  Dependencies installed."
	@# Copy starter bot to their own directory
	@if [ -d "bots/$(NAME)" ]; then \
		echo "  bots/$(NAME)/ already exists — skipping copy."; \
	else \
		cp -r bots/starter_bot "bots/$(NAME)"; \
		sed -i '' 's/"StarterBot"/"$(NAME)"/' "bots/$(NAME)/loadout.py"; \
		echo "  Created bots/$(NAME)/ from starter template."; \
	fi
	@echo ""
	@echo "  You're ready! Next steps:"
	@echo "    1. Edit bots/$(NAME)/loadout.py  — pick your gear"
	@echo "    2. Edit bots/$(NAME)/bot.py      — code your strategy"
	@echo "    3. make lobby BOT=bots/$(NAME)   — preview in gear room"
	@echo "    4. make deploy BOT=bots/$(NAME)  — send to arena"
	@echo ""

# Preview your bot in the gear room (auto-refreshes on file save)
# Usage: make lobby BOT=bots/my-bot
lobby:
	$(eval BOT ?= bots/starter_bot)
	@echo "=== Lobby — Gear Room ==="
	@echo "  Bot dir:     $(BOT)"
	@echo "  Game server: $(GAME_SERVER)"
	@echo "  Lobby UI:    http://localhost:5173/?lobby"
	@echo ""
	@echo "  Open the lobby UI in your browser, then edit your bot's"
	@echo "  loadout.py — the gear room updates live!"
	@echo ""
	lobby/.venv/bin/python -m lobby --server $(GAME_SERVER) $(BOT)

# Deploy your bot to the live game arena
# Usage: make deploy BOT=bots/my-bot
deploy:
	$(eval BOT ?= bots/starter_bot)
	@echo "=== Deploying $(BOT) to $(GAME_SERVER) ==="
	@echo ""
	lobby/.venv/bin/python $(BOT)/bot.py --server $(GAME_SERVER)

# Check if the game server is reachable and get status
status:
	@echo "Game server: $(GAME_SERVER)"
	@curl -s --connect-timeout 3 $(GAME_SERVER)/game/status | python3 -m json.tool \
		|| echo "  Could not reach $(GAME_SERVER) — is the game server running?"

# Show available commands
help:
	@echo ""
	@echo "  EAIS Game Night — Bot Arena"
	@echo "  ═══════════════════════════"
	@echo ""
	@echo "  COMPETITOR COMMANDS:"
	@echo "    make setup NAME=my-bot    Create your bot from the starter template"
	@echo "    make lobby BOT=bots/X     Preview your bot in the gear room"
	@echo "    make deploy BOT=bots/X    Deploy your bot to the live arena"
	@echo "    make status               Check game server status"
	@echo ""
	@echo "  Game server: $(GAME_SERVER)"
	@echo ""

# ============================================================
# Admin / Game Control (not for competitors)
# ============================================================

reset-game:
	@curl -s -X POST $(GAME_SERVER)/game/restart \
		-H 'Content-Type: application/json' -d '{}' | python3 -m json.tool
	@echo "Game reset."

add-bot:
	$(eval NAME ?= Bot-$(shell date +%s | tail -c 5))
	@curl -s -X POST $(GAME_SERVER)/game/join \
		-H 'Content-Type: application/json' \
		-d '{"name":"$(NAME)"}' | python3 -m json.tool

add-bots:
	@for name in Rick-Alpha Rick-Beta Rick-Gamma Rick-Delta; do \
		echo "Joining $$name..."; \
		curl -s -X POST $(GAME_SERVER)/game/join \
			-H 'Content-Type: application/json' \
			-d "{\"name\":\"$$name\"}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  {d.get(\"message\",d.get(\"error\",\"?\"))}  token={d.get(\"token\",\"N/A\")}')"; \
	done

bot-move:
	@if [ -z "$(TOKEN)" ]; then echo "Usage: make bot-move TOKEN=<token>"; exit 1; fi
	@for i in $$(seq 1 60); do \
		dir=$$(echo "north south east west" | tr ' ' '\n' | sort -R | head -1); \
		curl -s -X POST $(GAME_SERVER)/game/action \
			-H 'Content-Type: application/json' \
			-H 'Authorization: $(TOKEN)' \
			-d "{\"action\":\"move\",\"direction\":\"$$dir\"}" > /dev/null; \
		sleep 0.3; \
	done
	@echo "Bot movement done."

smart-bot:
	$(eval NAME ?= SmartBot)
	python3 bots/smart_bot.py --name $(NAME)

# ============================================================
# Dev targets
# ============================================================

run-mvp1:
	@echo "=== MVP 1: Standalone Phaser VS Clone ==="
	@echo "Open http://localhost:5173"
	cd spectator && make run-mvp1

run-mvp2:
	@echo "=== MVP 2: Server + Spectator ==="
	@echo "Terminal 1: cd battle-cab && make server-dev"
	@echo "Terminal 2: cd spectator && make dev"
	@echo "Terminal 3: make reset-game && make add-bots"
	@echo "Open http://localhost:5173/?server"

lobby-install:
	@test -d lobby/.venv || python3 -m venv lobby/.venv
	lobby/.venv/bin/pip install -r lobby/requirements.txt

starter-bot:
	$(eval NAME ?= StarterBot)
	lobby/.venv/bin/python bots/starter_bot/bot.py --name $(NAME)

install-spectator:
	cd spectator && npm install
