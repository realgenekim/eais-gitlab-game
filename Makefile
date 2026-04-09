# EAIS Game Night — Top-Level Makefile
# Orchestrates battle-cab server + Phaser spectator client

SERVER=http://localhost:33333

.PHONY: run-mvp1 run-mvp2 install-spectator reset-game add-bot add-bots bot-move status

# ============================================================
# MVP 1: Standalone Phaser VS clone (no server needed)
# ============================================================
run-mvp1:
	@echo "=== MVP 1: Standalone Phaser VS Clone ==="
	@echo "Open http://localhost:5173"
	cd spectator && make run-mvp1

# ============================================================
# MVP 2+: Phaser spectator + Clojure server
# ============================================================
run-mvp2:
	@echo "=== MVP 2: Server + Spectator ==="
	@echo "Terminal 1: cd battle-cab && make server-dev"
	@echo "Terminal 2: cd spectator && make dev"
	@echo "Terminal 3: make reset-game && make add-bots"
	@echo "Open http://localhost:5173/?server"

# ============================================================
# Game Control
# ============================================================

# Reset the game (clears all players, restarts tick counter)
reset-game:
	@curl -s -X POST $(SERVER)/game/restart \
		-H 'Content-Type: application/json' -d '{}' | python3 -m json.tool
	@echo "Game reset."

# Check game status
status:
	@curl -s $(SERVER)/game/status | python3 -m json.tool

# Add a single bot (name optional, default "Bot-N")
# Usage: make add-bot NAME=Rick-1
add-bot:
	$(eval NAME ?= Bot-$(shell date +%s | tail -c 5))
	@curl -s -X POST $(SERVER)/game/join \
		-H 'Content-Type: application/json' \
		-d '{"name":"$(NAME)"}' | python3 -m json.tool

# Add 4 bots with distinct names
add-bots:
	@for name in Rick-Alpha Rick-Beta Rick-Gamma Rick-Delta; do \
		echo "Joining $$name..."; \
		curl -s -X POST $(SERVER)/game/join \
			-H 'Content-Type: application/json' \
			-d "{\"name\":\"$$name\"}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  {d.get(\"message\",d.get(\"error\",\"?\"))}  token={d.get(\"token\",\"N/A\")}')"; \
	done

# Move a bot randomly (provide TOKEN=...)
# Usage: make bot-move TOKEN=abc-123
bot-move:
	@if [ -z "$(TOKEN)" ]; then echo "Usage: make bot-move TOKEN=<token>"; exit 1; fi
	@for i in $$(seq 1 60); do \
		dir=$$(echo "north south east west" | tr ' ' '\n' | sort -R | head -1); \
		curl -s -X POST $(SERVER)/game/action \
			-H 'Content-Type: application/json' \
			-H 'Authorization: $(TOKEN)' \
			-d "{\"action\":\"move\",\"direction\":\"$$dir\"}" > /dev/null; \
		sleep 0.3; \
	done
	@echo "Bot movement done."

# Run the smart bot (aims and shoots at enemies)
# Usage: make smart-bot NAME=MyBot
smart-bot:
	$(eval NAME ?= SmartBot)
	python3 bots/smart_bot.py --name $(NAME)

# ============================================================
# Demo — one command to launch everything
# ============================================================

# Launch all 4 bots in parallel (no stagger needed — lobby holds them)
bots:
	@cd sdk && python3 bot_hunter.py &
	@cd sdk && python3 bot_taxi.py &
	@cd sdk && python3 bot_berserker.py &
	@cd sdk && python3 bot_scavenger.py &
	@sleep 2 && echo "All bots launched. Check:" && curl -s $(SERVER)/game/status | python3 -m json.tool

# Kill all running bots
kill-bots:
	@pkill -f 'bot_hunter\|bot_taxi\|bot_berserker\|bot_scavenger' 2>/dev/null || true
	@echo "All bots killed."

# Full demo: reset game + launch 4 bots (server must be running)
demo: kill-bots
	@curl -s -X POST $(SERVER)/game/restart -H 'Content-Type: application/json' -d '{}' > /dev/null
	@sleep 1
	@cd sdk && python3 bot_hunter.py &
	@cd sdk && python3 bot_taxi.py &
	@cd sdk && python3 bot_berserker.py &
	@cd sdk && python3 bot_scavenger.py &
	@sleep 2 && curl -s $(SERVER)/game/status | python3 -m json.tool

# Start game from lobby (after bots have joined)
start:
	@curl -s -X POST $(SERVER)/game/start -H 'Content-Type: application/json' -d '{}' | python3 -m json.tool

# Run the AI commentator (requires ELEVENLABS_API_KEY and ANTHROPIC_API_KEY env vars)
commentator:
	cd sdk && python3 commentator.py

# ============================================================
# Setup
# ============================================================
install-spectator:
	cd spectator && npm install
