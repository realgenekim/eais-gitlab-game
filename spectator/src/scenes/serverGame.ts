// SERVER-CONNECTED GAME (MVP 3)
// All state from Clojure server via WebSocket. Phaser is render-only.

import { GameOptions } from '../gameOptions';

interface ServerPlayer {
    id: string;
    name: string;
    x: number;
    y: number;
    hp: number;
    alive: boolean;
    score: number;
    ammo: number;
}

interface ServerEnemy {
    id: string;
    x: number;
    y: number;
    hp: number;
    type: string;
}

interface ServerState {
    type: string;
    tick: number;
    players: ServerPlayer[];
    enemies: ServerEnemy[];
    map: { width: number; height: number };
}

// Map enemy type → sprite key + animation prefix
const ENEMY_SPRITES: Record<string, { key: string; prefix: string }> = {
    floopy:   { key: 'FloopyDoops', prefix: 'floopy' },
    squanchy: { key: 'Squanchy',    prefix: 'squanchy' },
    scary:    { key: 'FloopyDoops', prefix: 'floopy' },  // reuse until we add ScaryTerry sprites
};

export class ServerGame extends Phaser.Scene {

    constructor() {
        super({ key: 'ServerGame' });
    }

    ws              : WebSocket | null = null;
    connected       : boolean = false;
    playerSprites   : Map<string, Phaser.GameObjects.Sprite> = new Map();
    playerLabels    : Map<string, Phaser.GameObjects.Text> = new Map();
    enemySprites    : Map<string, Phaser.GameObjects.Sprite> = new Map();
    renderedShots   : Set<string> = new Set();  // track rendered shots by fired-tick+shooter
    statusText      : Phaser.GameObjects.Text;
    tileSize        : number = 48;
    mapWidth        : number = 21;
    mapHeight       : number = 19;

    frameMode       : boolean = false;
    currentFrame    : number = 0;

    create(): void {
        this.statusText = this.add.text(16, 16, 'Connecting...', {
            fontSize: '16px', color: '#4ecdc4', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2
        }).setDepth(100);

        // Check for frame=N in URL
        const params = new URLSearchParams(window.location.search);
        const frameParam = params.get('frame');

        if (frameParam !== null) {
            this.frameMode = true;
            this.currentFrame = parseInt(frameParam) || 0;
            this.loadFrame(this.currentFrame);
            this.setupFrameControls();
        } else {
            this.connectWebSocket();
        }
    }

    async loadFrame(tick: number): Promise<void> {
        const serverUrl = GameOptions.serverUrl || 'http://localhost:33333';
        try {
            const resp = await fetch(`${serverUrl}/game/frame?tick=${tick}`);
            if (resp.ok) {
                const state = await resp.json();
                this.currentFrame = tick;
                this.applyServerState(state);
                this.statusText.setText(`FRAME ${tick} | ${state.players.length} players | ${(state.enemies||[]).length} enemies | J/K to step`);
                // Update URL without reload
                const url = new URL(window.location.href);
                url.searchParams.set('frame', tick.toString());
                history.replaceState(null, '', url.toString());
            } else {
                const err = await resp.json();
                this.statusText.setText(`Frame ${tick} not found (${err['frame-count'] || 0} frames available)`);
            }
        } catch (e) {
            this.statusText.setText(`Error loading frame ${tick}`);
        }
    }

    setupFrameControls(): void {
        // J/K to step through frames, like the battle-cab test page
        this.input.keyboard!.on('keydown-J', () => {
            this.loadFrame(Math.max(0, this.currentFrame - 1));
        });
        this.input.keyboard!.on('keydown-K', () => {
            this.loadFrame(this.currentFrame + 1);
        });
        this.input.keyboard!.on('keydown-LEFT', () => {
            this.loadFrame(Math.max(0, this.currentFrame - 1));
        });
        this.input.keyboard!.on('keydown-RIGHT', () => {
            this.loadFrame(this.currentFrame + 1);
        });
        // Shift+J/K for 10-frame jumps
        this.input.keyboard!.on('keydown-H', () => {
            this.loadFrame(Math.max(0, this.currentFrame - 10));
        });
        this.input.keyboard!.on('keydown-L', () => {
            this.loadFrame(this.currentFrame + 10);
        });
    }

    connectWebSocket(): void {
        const url = GameOptions.serverWsUrl || 'ws://localhost:33333/spectate-ws';
        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
            this.connected = true;
            this.statusText.setText('Connected');
        };

        this.ws.onmessage = (event) => {
            try {
                const state: ServerState = JSON.parse(event.data);
                if (state.type === 'state') {
                    this.applyServerState(state);
                }
            } catch (e) {
                console.error('Parse error:', e);
            }
        };

        this.ws.onclose = () => {
            this.connected = false;
            this.statusText.setText('Disconnected — reconnecting...');
            this.time.delayedCall(2000, () => this.connectWebSocket());
        };

        this.ws.onerror = () => {};
    }

    gridToPixel(gx: number, gy: number): [number, number] {
        return [gx * this.tileSize + this.tileSize / 2,
                gy * this.tileSize + this.tileSize / 2];
    }

    applyServerState(state: ServerState): void {
        if (state.map) {
            this.mapWidth = state.map.width;
            this.mapHeight = state.map.height;
        }

        const enemyCount = state.enemies ? state.enemies.length : 0;
        this.statusText.setText(
            `Tick ${state.tick} | ${state.players.length} players | ${enemyCount} enemies`);

        // Dispatch to HTML scoreboard
        window.dispatchEvent(new CustomEvent('gameState', { detail: state }));

        this.updatePlayers(state);
        this.updateEnemies(state);
        this.renderShots(state);
    }

    renderShots(state: ServerState): void {
        const shots = (state as any)['recent-shots'] || [];
        for (const shot of shots) {
            // Deduplicate: only render each shot once
            const firedTick = shot['fired-tick'] || 0;
            const shotKey = `${shot['shooter-id']}-${firedTick}-${shot.direction}`;
            if (this.renderedShots.has(shotKey)) continue;
            this.renderedShots.add(shotKey);

            const path: number[][] = shot.path || [];
            if (path.length === 0) continue;

            const [ox, oy] = this.gridToPixel(shot.origin[0], shot.origin[1]);
            const lastCell = path[path.length - 1];
            const [tx, ty] = this.gridToPixel(lastCell[0], lastCell[1]);

            // Draw tracer line
            const line = this.add.graphics();
            line.lineStyle(2, 0xff4400, 0.8);
            line.beginPath();
            line.moveTo(ox, oy);
            line.lineTo(tx, ty);
            line.strokePath();
            line.setDepth(15);

            // Bright tip at impact point
            const tip = this.add.circle(tx, ty, 4, 0xffcc00, 1);
            tip.setDepth(16);

            // Fade out and destroy quickly
            this.tweens.add({
                targets: [line, tip],
                alpha: 0,
                duration: 300,
                onComplete: () => { line.destroy(); tip.destroy(); }
            });

            // Clean up old shot keys (prevent memory leak)
            if (this.renderedShots.size > 200) {
                this.renderedShots.clear();
            }
        }
    }

    updatePlayers(state: ServerState): void {
        const seenIds = new Set<string>();
        const tickMs = GameOptions.tickMs || 250;

        for (const p of state.players) {
            seenIds.add(p.id);
            const [targetX, targetY] = this.gridToPixel(p.x, p.y);

            if (this.playerSprites.has(p.id)) {
                const sprite = this.playerSprites.get(p.id)!;
                const label = this.playerLabels.get(p.id)!;

                if (p.alive) {
                    sprite.setVisible(true);
                    label.setVisible(true);
                    this.tweens.killTweensOf(sprite);
                    this.tweens.killTweensOf(label);

                    const dx = targetX - sprite.x;
                    const dy = targetY - sprite.y;

                    this.tweens.add({ targets: sprite, x: targetX, y: targetY, duration: tickMs, ease: 'Linear' });
                    this.tweens.add({ targets: label, x: targetX, y: targetY - 40, duration: tickMs, ease: 'Linear' });

                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        if (Math.abs(dy) > Math.abs(dx)) {
                            sprite.play(dy > 0 ? 'rick-walk-down' : 'rick-walk-up', true);
                            sprite.setFlipX(false);
                        } else {
                            sprite.play('rick-walk-side', true);
                            sprite.setFlipX(dx < 0);
                        }
                    } else {
                        sprite.play('rick-idle', true);
                    }
                    label.setText(`${p.name} [${p.hp}hp]`);
                } else {
                    sprite.setVisible(false);
                    label.setVisible(false);
                }
            } else {
                const sprite = this.add.sprite(targetX, targetY, 'RickDefault', 'RickDefault_front');
                sprite.setScale(0.5);
                sprite.setDepth(10);
                this.playerSprites.set(p.id, sprite);

                const label = this.add.text(targetX, targetY - 40, p.name, {
                    fontSize: '12px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(50);
                this.playerLabels.set(p.id, label);

                if (!p.alive) { sprite.setVisible(false); label.setVisible(false); }
            }
        }

        // Remove disconnected players
        for (const [id, sprite] of this.playerSprites) {
            if (!seenIds.has(id)) {
                sprite.destroy();
                this.playerLabels.get(id)?.destroy();
                this.playerSprites.delete(id);
                this.playerLabels.delete(id);
            }
        }
    }

    updateEnemies(state: ServerState): void {
        const enemies = state.enemies || [];
        const seenIds = new Set<string>();
        const tickMs = GameOptions.tickMs || 250;

        for (const e of enemies) {
            seenIds.add(e.id);
            const [targetX, targetY] = this.gridToPixel(e.x, e.y);
            const spriteInfo = ENEMY_SPRITES[e.type] || ENEMY_SPRITES.floopy;

            if (this.enemySprites.has(e.id)) {
                // Existing enemy — tween to new position
                const sprite = this.enemySprites.get(e.id)!;
                this.tweens.killTweensOf(sprite);

                const dx = targetX - sprite.x;
                const dy = targetY - sprite.y;

                this.tweens.add({ targets: sprite, x: targetX, y: targetY, duration: tickMs, ease: 'Linear' });

                // Walk animation based on direction
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    if (Math.abs(dy) > Math.abs(dx)) {
                        sprite.play(dy > 0 ? `${spriteInfo.prefix}-walk-down` : `${spriteInfo.prefix}-walk-up`, true);
                        sprite.setFlipX(false);
                    } else {
                        sprite.play(`${spriteInfo.prefix}-walk-side`, true);
                        sprite.setFlipX(dx < 0);
                    }
                }
            } else {
                // New enemy — create sprite
                const sprite = this.add.sprite(targetX, targetY,
                    spriteInfo.key, `${spriteInfo.key}_down_1`);
                sprite.setScale(0.3);
                sprite.setDepth(5);
                this.enemySprites.set(e.id, sprite);
            }
        }

        // Remove dead enemies
        for (const [id, sprite] of this.enemySprites) {
            if (!seenIds.has(id)) {
                // Quick death flash then destroy
                this.tweens.add({
                    targets: sprite,
                    alpha: 0,
                    scale: 0.1,
                    duration: 150,
                    onComplete: () => sprite.destroy()
                });
                this.enemySprites.delete(id);
            }
        }
    }
}
