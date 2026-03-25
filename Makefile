# Default target when running 'make' without arguments
.DEFAULT_GOAL := help

# Add ~/bin to PATH for Clojure CLI tools
export PATH := $(HOME)/bin:$(PATH)

# Server port (default: 8080)
PORT ?= 33333

# Start nREPL server (auto-assigns port, writes to .nrepl-port)
nrepl:
	clojure -M:nrepl

# Configure clj-kondo with library configs (run once after adding dependencies)
clj-kondo-config:
	@echo "🔧 Configuring clj-kondo with library configs..."
	clj-kondo --lint "$$(clojure -Spath)" --dependencies --parallel --copy-configs
	@echo "✅ clj-kondo configs updated in .clj-kondo/"

# ========================================
# MCP Server Configuration
# ========================================

# Configure MCP server in Claude Code (dynamically uses current directory)
mcp-configure:
	@echo "🔧 Configuring MCP server in Claude Code..."
	@echo ""
	@echo "Adding Clojure MCP (project-specific tools)..."
	claude mcp add clojure-mcp -- /bin/sh -c 'cd $(shell pwd) && clojure -Tmcp start :config-profile :cli-assist'
	@echo ""
	@echo "✅ MCP server configured!"

# Remove MCP server
mcp-remove:
	@echo "🗑️  Removing MCP server..."
	-claude mcp remove clojure-mcp
	@echo "✅ MCP server removed!"

# Run Clojure MCP server locally (for testing)
mcp-run:
	@echo "🚀 Starting Clojure MCP server..."
	@echo "   Reading port from: $(shell pwd)/.nrepl-port"
	cd $(shell pwd) && clojure -Tmcp start :config-profile :cli-assist

# ========================================
# Testing
# ========================================

# Run tests with kaocha - watch mode
runtests:
	@echo "Running tests with watcher..."
	bin/kaocha --watch --reporter kaocha.report.progress/report

# Run tests once with fail-fast
runtests-once:
	@echo "Running tests with fail-fast..."
	bin/kaocha --fail-fast

# ========================================
# Development
# ========================================

# Default: start in dev mode
server: server-dev

# Dev mode: auto-reload, browser-reload
server-dev:
	@echo "🚕 Starting Cab Battle DEV server (port $(PORT), auto-reload)..."
	ENV=dev PORT=$(PORT) clojure -A:dev -M -m game.server

# Production mode
server-prod:
	@echo "🚕 Starting Cab Battle server (port $(PORT), production mode)..."
	PORT=$(PORT) clojure -M -m game.server

# Run starter Python bot
bot:
	PYTHONUNBUFFERED=1 python3 starter-bots/python/bot.py --server http://localhost:$(PORT) --name "PyBot-$$$$"

# Stop server running on configured port
stop:
	lsof -ti :$(PORT) | xargs kill -9 || true

# Test that application compiles without errors
server-test-run:
	@echo "Testing application compilation..."
	clojure -M -m game.server --help 2>&1 | head -1 || (echo "✗ Application failed to compile" && exit 1)
	@echo "✓ Application compiled successfully"

# Start REPL
repl:
	clj

# Clean compiled artifacts
clean:
	rm -rf .cpcache/ .nrepl-port target/

# Help
help:
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@echo "  🚕 Cab Battle — EAIS Game Night Server"
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@echo ""
	@echo "🔧 Setup:"
	@echo "  make nrepl                - Start nREPL server"
	@echo "  make clj-kondo-config     - Configure clj-kondo"
	@echo "  make mcp-configure        - Configure Clojure MCP for Claude Code"
	@echo ""
	@echo "🧪 Testing:"
	@echo "  make runtests             - Run tests with watcher"
	@echo "  make runtests-once        - Run tests once with fail-fast"
	@echo ""
	@echo "🚀 Development:"
	@echo "  make server               - Start dev server (default)"
	@echo "  make server-dev           - Start dev server (auto-reload)"
	@echo "  make server-prod          - Start production server"
	@echo "  make stop                 - Stop server on port $(PORT)"
	@echo "  make repl                 - Start basic REPL"
	@echo "  make clean                - Clean compiled artifacts"
	@echo ""
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

.PHONY: nrepl clj-kondo-config mcp-configure mcp-remove mcp-run runtests runtests-once server server-dev server-prod stop server-test-run repl clean help
