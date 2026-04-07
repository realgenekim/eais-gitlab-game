// SERVER-CONNECTED GAME (MVP 3)
// All state from Clojure server via WebSocket. Phaser is render-only.

import { GameOptions } from '../gameOptions';
import { RICK_VARIANTS } from './preloadAssets';

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
    map: { width: number; height: number; walls?: number[][] };
    'shrink-warning'?: number[][];
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
    playerHpBars    : Map<string, Phaser.GameObjects.Graphics> = new Map();
    enemySprites    : Map<string, Phaser.GameObjects.Sprite> = new Map();
    renderedShots   : Set<string> = new Set();  // track rendered shots by fired-tick+shooter
    shrinkGraphics  : Phaser.GameObjects.Graphics | null = null;
    shrinkTween     : Phaser.Tweens.Tween | null = null;
    wallGraphics    : Phaser.GameObjects.Graphics | null = null;
    lastWallCount   : number = -1;
    playerAvatars   : Map<string, string> = new Map();  // player-id → rick variant
    nextAvatarIndex : number = 0;
    lastEnemyCount  : number = 0;
    waveNumber      : number = 0;
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

    getAvatar(playerId: string): { key: string; prefix: string } {
        if (!this.playerAvatars.has(playerId)) {
            const variant = RICK_VARIANTS[this.nextAvatarIndex % RICK_VARIANTS.length];
            this.playerAvatars.set(playerId, variant);
            this.nextAvatarIndex++;
        }
        const variant = this.playerAvatars.get(playerId)!;
        const prefix = variant.toLowerCase().replace(/[^a-z0-9]/g, '');
        return { key: variant, prefix };
    }

    gridToPixel(gx: number, gy: number): [number, number] {
        return [gx * this.tileSize + this.tileSize / 2,
                gy * this.tileSize + this.tileSize / 2];
    }

    applyServerState(state: ServerState): void {
        if (state.map) {
            this.mapWidth = state.map.width;
            this.mapHeight = state.map.height;
            // Dynamically size tiles to fill the canvas
            const canvasW = this.scale.width;
            const canvasH = this.scale.height;
            this.tileSize = Math.max(1, Math.floor(Math.min(
                canvasW / this.mapWidth,
                canvasH / this.mapHeight
            )));
        }

        const enemyCount = state.enemies ? state.enemies.length : 0;
        this.statusText.setText(
            `Tick ${state.tick} | ${state.players.length} players | ${enemyCount} enemies`);

        // Dispatch to HTML scoreboard
        window.dispatchEvent(new CustomEvent('gameState', { detail: state }));

        this.renderWalls(state);
        this.updatePlayers(state);
        this.detectWave(state);
        this.updateEnemies(state);
        this.renderShots(state);
        this.renderShrinkWarning(state);
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

            // Floating damage text at impact
            if (shot['hit-id']) {
                const dmgText = this.add.text(tx, ty - 10, '-30', {
                    fontSize: '16px', color: '#ff4400', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(100);
                this.tweens.add({
                    targets: dmgText,
                    y: ty - 50,
                    alpha: 0,
                    duration: 600,
                    ease: 'Power2',
                    onComplete: () => dmgText.destroy()
                });
            }

            // Clean up old shot keys (prevent memory leak)
            if (this.renderedShots.size > 200) {
                this.renderedShots.clear();
            }
        }
    }

    // Store last known HP per player for update() loop
    playerHpValues  : Map<string, number> = new Map();

    drawAllHpBars(): void {
        for (const [id, sprite] of this.playerSprites) {
            if (!sprite.visible) {
                if (this.playerHpBars.has(id)) this.playerHpBars.get(id)!.setVisible(false);
                continue;
            }
            let bar = this.playerHpBars.get(id);
            if (!bar) {
                bar = this.add.graphics().setDepth(60);
                this.playerHpBars.set(id, bar);
            }
            bar.clear();
            bar.setVisible(true);
            const w = 40;
            const h = 5;
            const x = sprite.x - w / 2;
            const y = sprite.y - 45;
            const hp = this.playerHpValues.get(id) || 500;
            const pct = Math.max(0, hp / 500);
            // Background
            bar.fillStyle(0x333333, 0.8);
            bar.fillRect(x, y, w, h);
            // HP fill
            const color = pct > 0.5 ? 0x4ecdc4 : pct > 0.25 ? 0xffe66d : 0xff6b6b;
            bar.fillStyle(color, 1);
            bar.fillRect(x, y, w * pct, h);
        }
    }

    detectWave(state: ServerState): void {
        const currentCount = (state.enemies || []).length;
        // If enemy count jumped by 3+ in one tick, it's a new wave
        if (currentCount >= this.lastEnemyCount + 3 && this.lastEnemyCount >= 0) {
            this.waveNumber++;
            const enemyTypes = [...new Set((state.enemies || []).map(e => e.type))];
            const typeLabel = enemyTypes.length > 1
                ? enemyTypes.join(' + ').toUpperCase()
                : (enemyTypes[0] || 'ENEMIES').toUpperCase();

            const waveText = this.add.text(
                this.cameras.main.centerX, this.cameras.main.centerY - 50,
                `WAVE ${this.waveNumber}`, {
                    fontSize: '48px', color: '#ff6b6b', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 6
                }).setOrigin(0.5).setDepth(200).setAlpha(0);

            const subText = this.add.text(
                this.cameras.main.centerX, this.cameras.main.centerY + 10,
                typeLabel + ' INCOMING!', {
                    fontSize: '20px', color: '#ffe66d', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 4
                }).setOrigin(0.5).setDepth(200).setAlpha(0);

            // Animate in, hold, fade out
            this.tweens.add({
                targets: [waveText, subText],
                alpha: 1,
                duration: 300,
                hold: 1500,
                yoyo: true,
                onComplete: () => { waveText.destroy(); subText.destroy(); }
            });
        }
        this.lastEnemyCount = currentCount;
    }

    updatePlayers(state: ServerState): void {
        const seenIds = new Set<string>();
        const tickMs = GameOptions.tickMs || 250;

        for (const p of state.players) {
            seenIds.add(p.id);
            const [targetX, targetY] = this.gridToPixel(p.x, p.y);
            const avatar = this.getAvatar(p.id);

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
                            sprite.play(dy > 0 ? `${avatar.prefix}-walk-down` : `${avatar.prefix}-walk-up`, true);
                            sprite.setFlipX(false);
                        } else {
                            sprite.play(`${avatar.prefix}-walk-side`, true);
                            sprite.setFlipX(dx < 0);
                        }
                    } else {
                        sprite.play(`${avatar.prefix}-idle`, true);
                    }
                    label.setText(p.name);
                    this.playerHpValues.set(p.id, p.hp);
                } else {
                    sprite.setVisible(false);
                    label.setVisible(false);
                }
            } else {
                // New player — use assigned avatar, down_1 frame (consistent size)
                const sprite = this.add.sprite(targetX, targetY,
                    avatar.key, `${avatar.key}_down_1`);
                sprite.setScale(0.5);
                sprite.setDepth(10);
                this.playerSprites.set(p.id, sprite);

                const label = this.add.text(targetX, targetY - 40, p.name, {
                    fontSize: '11px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(50);
                this.playerLabels.set(p.id, label);
                this.playerHpValues.set(p.id, p.hp);

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

        // Remove dead enemies — death effects
        let killCount = 0;
        for (const [id, sprite] of this.enemySprites) {
            if (!seenIds.has(id)) {
                killCount++;
                const dx = sprite.x;
                const dy = sprite.y;

                // Score popup
                const scoreText = this.add.text(dx, dy - 15, '+10', {
                    fontSize: '14px', color: '#ffe66d', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(100);
                this.tweens.add({
                    targets: scoreText,
                    y: dy - 55,
                    alpha: 0,
                    duration: 800,
                    ease: 'Power2',
                    onComplete: () => scoreText.destroy()
                });

                // Death flash + shrink
                this.tweens.add({
                    targets: sprite,
                    alpha: 0,
                    scale: 0.05,
                    duration: 200,
                    onComplete: () => sprite.destroy()
                });
                this.enemySprites.delete(id);
            }
        }

        // Screen shake when enemies die
        if (killCount > 0) {
            this.cameras.main.shake(80, 0.003 * killCount);
        }
    }

    renderWalls(state: ServerState): void {
        const walls = state.map?.walls || [];

        // Only redraw when wall count changes (perf: avoid redrawing every tick)
        if (walls.length === this.lastWallCount) return;
        this.lastWallCount = walls.length;

        if (!this.wallGraphics) {
            this.wallGraphics = this.add.graphics();
            this.wallGraphics.setDepth(1); // above background, below everything else
        }

        this.wallGraphics.clear();

        const ts = Math.max(1, this.tileSize);  // minimum 1x1
        for (const cell of walls) {
            const [gx, gy] = cell;
            const px = gx * ts;
            const py = gy * ts;
            // Dark wall fill
            this.wallGraphics.fillStyle(0x2a2a3a, 1.0);
            this.wallGraphics.fillRect(px, py, ts, ts);
            // Subtle border
            this.wallGraphics.lineStyle(1, 0x3a3a4a, 0.5);
            this.wallGraphics.strokeRect(px, py, ts, ts);
        }
    }

    renderShrinkWarning(state: ServerState): void {
        const cells = state['shrink-warning'] || [];

        // Create graphics object on first use
        if (!this.shrinkGraphics) {
            this.shrinkGraphics = this.add.graphics();
            this.shrinkGraphics.setDepth(3); // above floor, below sprites
        }

        this.shrinkGraphics.clear();

        if (cells.length === 0) {
            // Kill pulse tween when no warnings
            if (this.shrinkTween) {
                this.shrinkTween.stop();
                this.shrinkTween = null;
            }
            return;
        }

        // Draw warning cells — red/orange overlay
        for (const cell of cells) {
            const [gx, gy] = cell;
            const px = gx * this.tileSize;
            const py = gy * this.tileSize;
            this.shrinkGraphics.fillStyle(0xff4400, 0.35);
            this.shrinkGraphics.fillRect(px, py, this.tileSize, this.tileSize);
            // Border
            this.shrinkGraphics.lineStyle(1, 0xff6600, 0.6);
            this.shrinkGraphics.strokeRect(px, py, this.tileSize, this.tileSize);
        }

        // Pulsing alpha animation
        if (!this.shrinkTween) {
            this.shrinkTween = this.tweens.add({
                targets: this.shrinkGraphics,
                alpha: { from: 0.4, to: 1.0 },
                duration: 500,
                yoyo: true,
                repeat: -1,
                ease: 'Sine.easeInOut'
            });
        }
    }

    update(): void {
        // HP bars follow sprite positions every frame (smooth during tweens)
        this.drawAllHpBars();
    }
}
