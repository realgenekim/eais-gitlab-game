# VS Arena — Phaser Spectator Client

Based on [Emanuele Feronato's Phaser VS prototype](https://emanueleferonato.com/2024/11/29/quick-html5-prototype-of-vampire-survivors-built-with-phaser-like-the-original-game/).

## Quick Start

```bash
cd spectator
npm install
npm start        # Vite dev server on http://localhost:5173
```

## MVP Progression

1. **MVP 1 (current):** Standalone Phaser VS clone with placeholder sprites
2. **MVP 1b:** Swap placeholders for rick-survival sprite sheets
3. **MVP 2:** Connect to Clojure server via WebSocket, render server state
4. **MVP 3:** Server-side enemy spawning and wave system
5. **MVP 4:** Manual targeting (bots aim, no auto-fire)
6. **MVP 5:** Full game loop with teams, waves, shrink

## Architecture

Currently runs as a standalone client (Feronato's prototype).
Will evolve into a render-only spectator that receives state from the Clojure server.
