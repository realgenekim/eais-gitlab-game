// MAIN GAME FILE
// Spectator client — renders game state
//
// URL modes:
//   http://localhost:5173           → MVP 1 (standalone, local game logic)
//   http://localhost:5173?server    → MVP 2 (connected to Clojure server via WebSocket)

import Phaser from 'phaser';
import { PreloadAssets } from './scenes/preloadAssets';
import { PlayGame } from './scenes/playGame';
import { ServerGame } from './scenes/serverGame';
import { LobbyScene } from './scenes/lobbyScene';
import { GameOptions } from './gameOptions';

const configObject: Phaser.Types.Core.GameConfig = {
    type: Phaser.WEBGL,
    backgroundColor: GameOptions.gameBackgroundColor,
    scale: {
        mode: Phaser.Scale.FIT,
        autoCenter: Phaser.Scale.CENTER_BOTH,
        parent: 'thegame',
        width: GameOptions.gameSize.width,
        height: GameOptions.gameSize.height
    },
    scene: [PreloadAssets, PlayGame, ServerGame, LobbyScene],
    physics: { default: 'arcade' },
}

new Phaser.Game(configObject);
