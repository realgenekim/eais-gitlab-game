# EAIS Game Night — Top-Level Makefile
# Orchestrates battle-cab server + Phaser spectator client

.PHONY: run-mvp1 run-mvp2 install-spectator

# ============================================================
# MVP 1: Standalone Phaser VS clone (no server needed)
# ============================================================
run-mvp1:
	@echo "=== MVP 1: Standalone Phaser VS Clone ==="
	@echo "Open http://localhost:5173"
	@echo ""
	cd spectator && make run-mvp1

# ============================================================
# MVP 2: Phaser spectator + Clojure server (TODO)
# ============================================================
run-mvp2:
	@echo "=== MVP 2: Server + Spectator ==="
	@echo "Terminal 1: cd battle-cab && make server-dev"
	@echo "Terminal 2: cd spectator && make dev"
	@echo ""
	@echo "Not yet wired — run each manually for now."

# ============================================================
# Setup
# ============================================================
install-spectator:
	cd spectator && npm install
