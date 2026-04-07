# VS Arena — Phaser Spectator Client
# Usage: make run-mvp1

.PHONY: install dev run-mvp1 build clean

# One-time setup
install:
	npm install

# Dev server (Vite hot-reload on http://localhost:5173)
dev: install
	npx vite --port 5173

# MVP 1: Standalone Phaser VS clone with rick-survival sprites
run-mvp1: install
	@echo "=== MVP 1: Standalone Phaser VS Clone ==="
	@echo "Open http://localhost:5173"
	@echo "WASD to move, auto-fire kills enemies, collect XP gems"
	@echo ""
	npx vite --port 5173

# MVP 2: Server-connected mode (requires battle-cab server running)
# Start server first: cd ../battle-cab && make server-dev
run-mvp2: install
	@echo "=== MVP 2: Server-Connected Spectator ==="
	@echo "Requires battle-cab server on port 33333"
	@echo "Open http://localhost:5173/?server"
	@echo ""
	npx vite --port 5173

# Production build
build: install
	npx vite build

# Clean
clean:
	rm -rf node_modules dist
