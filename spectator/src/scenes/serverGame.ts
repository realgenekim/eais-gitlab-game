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
    points: number;
    items: string[];
    buffs: Record<string, any>;
    debuffs: Record<string, any>;
    'has-avatar'?: boolean;
}

interface ServerEnemy {
    id: string;
    x: number;
    y: number;
    hp: number;
    type: string;
}

interface ServerCrate {
    id: string;
    x: number;
    y: number;
    tier: string;
    cost: number;
}

interface ServerState {
    type: string;
    phase: string;
    tick: number;
    players: ServerPlayer[];
    enemies: ServerEnemy[];
    crates?: ServerCrate[];
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
    customAvatarLoaded : Set<string> = new Set();     // player-ids with loaded custom textures
    customAvatarLoading: Set<string> = new Set();     // player-ids currently loading
    lastEnemyCount  : number = 0;
    waveNumber      : number = 0;
    statusText      : Phaser.GameObjects.Text;
    tileSize        : number = 48;
    mapWidth        : number = 21;
    mapHeight       : number = 19;
    arenaOffsetX    : number = 0;  // pixel offset to center arena with crowd padding
    arenaOffsetY    : number = 0;
    crowdPadding    : number = 160; // pixels reserved for crowd on each side

    crateSprites    : Map<string, Phaser.GameObjects.Graphics> = new Map();
    crateLabels     : Map<string, Phaser.GameObjects.Text> = new Map();

    frameMode       : boolean = false;
    currentFrame    : number = 0;
    logoPlaced      : boolean = false;
    crowdPlaced     : boolean = false;
    crowdSprites    : Phaser.GameObjects.Shape[] = [];

    create(): void {
        // Status text with solid black background for visibility
        const statusBg = this.add.rectangle(0, 0, 500, 30, 0x000000, 0.85)
            .setOrigin(0, 0).setDepth(99);
        this.statusText = this.add.text(10, 6, 'Connecting...', {
            fontSize: '18px', color: '#4ecdc4', fontFamily: 'monospace',
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

    getAvatar(playerId: string): { key: string; prefix: string; custom: boolean } {
        if (this.customAvatarLoaded.has(playerId)) {
            return { key: `custom-${playerId}`, prefix: '', custom: true };
        }
        if (!this.playerAvatars.has(playerId)) {
            const variant = RICK_VARIANTS[this.nextAvatarIndex % RICK_VARIANTS.length];
            this.playerAvatars.set(playerId, variant);
            this.nextAvatarIndex++;
        }
        const variant = this.playerAvatars.get(playerId)!;
        const prefix = variant.toLowerCase().replace(/[^a-z0-9]/g, '');
        return { key: variant, prefix, custom: false };
    }

    loadCustomAvatar(playerId: string): void {
        if (this.customAvatarLoading.has(playerId) || this.customAvatarLoaded.has(playerId)) return;
        this.customAvatarLoading.add(playerId);

        const serverUrl = GameOptions.serverUrl || 'http://localhost:33333';
        const url = `${serverUrl}/game/avatar/${playerId}`;
        const textureKey = `custom-${playerId}`;

        fetch(url)
            .then(resp => {
                if (!resp.ok) throw new Error('No avatar');
                return resp.blob();
            })
            .then(blob => {
                const img = new Image();
                img.src = URL.createObjectURL(blob);
                img.onload = () => {
                    if (!this.textures.exists(textureKey)) {
                        this.textures.addImage(textureKey, img);
                    }
                    this.customAvatarLoaded.add(playerId);
                    this.customAvatarLoading.delete(playerId);
                    // Swap sprite if already rendered with Rick texture
                    if (this.playerSprites.has(playerId)) {
                        const sprite = this.playerSprites.get(playerId)!;
                        sprite.setTexture(textureKey);
                        sprite.setDisplaySize(this.tileSize, this.tileSize);
                        sprite.stop();
                    }
                };
                img.onerror = () => { this.customAvatarLoading.delete(playerId); };
            })
            .catch(() => { this.customAvatarLoading.delete(playerId); });
    }

    gridToPixel(gx: number, gy: number): [number, number] {
        return [this.arenaOffsetX + gx * this.tileSize + this.tileSize / 2,
                this.arenaOffsetY + gy * this.tileSize + this.tileSize / 2];
    }

    applyServerState(state: ServerState): void {
        if (state.map) {
            this.mapWidth = state.map.width;
            this.mapHeight = state.map.height;
            // Dynamically size tiles to fill the canvas
            const canvasW = this.scale.width;
            const canvasH = this.scale.height;
            // Leave padding for crowd on all sides
            const usableW = canvasW - this.crowdPadding * 2;
            const usableH = canvasH - this.crowdPadding * 2;
            this.tileSize = Math.max(1, Math.floor(Math.min(
                usableW / this.mapWidth,
                usableH / this.mapHeight
            )));
            this.arenaOffsetX = Math.floor((canvasW - this.mapWidth * this.tileSize) / 2);
            this.arenaOffsetY = Math.floor((canvasH - this.mapHeight * this.tileSize) / 2);
        }

        const enemyCount = state.enemies ? state.enemies.length : 0;
        this.statusText.setText(
            `Tick ${state.tick} | ${state.players.length} players | ${enemyCount} enemies`);

        // Dispatch to HTML scoreboard
        window.dispatchEvent(new CustomEvent('gameState', { detail: state }));

        // Place crowd spectators around the arena (once)
        if (!this.crowdPlaced && this.tileSize > 1) {
            this.drawCrowd();
            this.crowdPlaced = true;
        }

        // Place IT Revolution logo as floor watermark (once we know map size)
        if (!this.logoPlaced && this.textures.exists('itrev-logo')) {
            const cx = this.arenaOffsetX + (this.mapWidth * this.tileSize) / 2;
            const cy = this.arenaOffsetY + (this.mapHeight * this.tileSize) / 2;
            const logo = this.add.image(cx, cy, 'itrev-logo');
            logo.setAlpha(0.08);
            logo.setDepth(0);  // behind everything
            const targetW = this.mapWidth * this.tileSize * 0.5;
            logo.setScale(targetW / logo.width);
            this.logoPlaced = true;
        }

        this.renderWalls(state);
        this.updatePlayers(state);
        this.detectWave(state);
        this.updateEnemies(state);
        this.renderCrates(state);
        this.renderShots(state);
        this.renderShrinkWarning(state);
    }

    renderCrates(state: ServerState): void {
        const crates = state.crates || [];
        const crateIds = new Set(crates.map(c => c.id));

        // Remove sprites for crates that no longer exist
        for (const [id, gfx] of this.crateSprites) {
            if (!crateIds.has(id)) {
                gfx.destroy();
                this.crateSprites.delete(id);
                const label = this.crateLabels.get(id);
                if (label) { label.destroy(); this.crateLabels.delete(id); }
            }
        }

        for (const crate of crates) {
            const [px, py] = this.gridToPixel(crate.x, crate.y);
            const size = this.tileSize * 0.4;

            if (!this.crateSprites.has(crate.id)) {
                // Create new crate visual
                const gfx = this.add.graphics();
                const color = crate.tier === 'gold' ? 0xffd700
                            : crate.tier === 'silver' ? 0xc0c0c0
                            : 0xc084fc;
                gfx.fillStyle(color, 0.8);
                gfx.fillRoundedRect(-size/2, -size/2, size, size, 4);
                gfx.lineStyle(2, 0xffffff, 0.5);
                gfx.strokeRoundedRect(-size/2, -size/2, size, size, 4);
                gfx.setPosition(px, py);
                gfx.setDepth(12);
                this.crateSprites.set(crate.id, gfx);

                // Cost label
                const label = this.add.text(px, py + size/2 + 4, crate.cost + 'pt', {
                    fontSize: '10px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 2
                }).setOrigin(0.5, 0).setDepth(13);
                this.crateLabels.set(crate.id, label);

                // Spawn animation: scale bounce
                gfx.setScale(0);
                this.tweens.add({
                    targets: gfx,
                    scaleX: 1, scaleY: 1,
                    duration: 300,
                    ease: 'Back.easeOut'
                });

                // Gold crates get a glow pulse
                if (crate.tier === 'gold') {
                    this.tweens.add({
                        targets: gfx,
                        alpha: { from: 1, to: 0.6 },
                        duration: 600,
                        yoyo: true,
                        repeat: -1
                    });
                }
            } else {
                // Update position if needed
                const gfx = this.crateSprites.get(crate.id)!;
                gfx.setPosition(px, py);
                const label = this.crateLabels.get(crate.id);
                if (label) label.setPosition(px, py + size/2 + 4);
            }
        }
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

            // Floating damage text at impact + explosion
            if (shot['hit-id']) {
                const dmgText = this.add.text(tx, ty - 10, '-30', {
                    fontSize: '24px', color: '#ff4400', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 5
                }).setOrigin(0.5).setDepth(200);
                this.tweens.add({
                    targets: dmgText,
                    y: ty - 70,
                    alpha: 0,
                    scale: 1.3,
                    duration: 900,
                    ease: 'Power2',
                    onComplete: () => dmgText.destroy()
                });
                // Impact explosion!
                this.spawnImpact(tx, ty);
                this.cameras.main.shake(120, 0.005);
            }

            // Clean up old shot keys (prevent memory leak)
            if (this.renderedShots.size > 200) {
                this.renderedShots.clear();
            }
        }
    }

    // Store last known HP per player for damage detection
    playerHpValues  : Map<string, number> = new Map();
    playerLastHp    : Map<string, number> = new Map();

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
            const w = 52;
            const h = 8;
            const x = sprite.x - w / 2;
            const y = sprite.y - 48;
            const hp = this.playerHpValues.get(id) || 500;
            const pct = Math.max(0, hp / 500);
            // Background
            bar.fillStyle(0x111111, 0.9);
            bar.fillRect(x - 1, y - 1, w + 2, h + 2);
            bar.fillStyle(0x333333, 0.8);
            bar.fillRect(x, y, w, h);
            // HP fill
            const color = pct > 0.5 ? 0x4ecdc4 : pct > 0.25 ? 0xffe66d : 0xff6b6b;
            bar.fillStyle(color, 1);
            bar.fillRect(x, y, w * pct, h);
            // Border
            bar.lineStyle(1, 0x666666, 0.6);
            bar.strokeRect(x - 1, y - 1, w + 2, h + 2);
        }
    }

    detectWave(state: ServerState): void {
        const currentCount = (state.enemies || []).length;
        // Use server's authoritative wave number
        const serverWave = (state as any)['wave-number'] || 0;
        if (serverWave > this.waveNumber) {
            this.waveNumber = serverWave;
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
            // Trigger custom avatar loading if server says they have one
            if (p['has-avatar']) {
                this.loadCustomAvatar(p.id);
            }
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
                    this.tweens.add({ targets: label, x: targetX, y: targetY - 65, duration: tickMs, ease: 'Linear' });

                    if (avatar.custom) {
                        // Custom avatar: no animations, just flip on horizontal movement
                        if (Math.abs(dx) > 2) {
                            sprite.setFlipX(dx < 0);
                        }
                    } else if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
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

                    // DAMAGE DETECTION — flash and scream when hit
                    const prevHp = this.playerLastHp.get(p.id) ?? p.hp;
                    const dmg = prevHp - p.hp;
                    if (dmg > 0 && prevHp > 0) {
                        // Red flash on the sprite
                        sprite.setTint(0xff0000);
                        this.time.delayedCall(150, () => sprite.clearTint());

                        // Big floating damage number
                        const dmgNum = this.add.text(targetX, targetY - 30, `-${dmg}`, {
                            fontSize: '28px', color: '#ff2222', fontFamily: 'monospace',
                            stroke: '#000', strokeThickness: 6
                        }).setOrigin(0.5).setDepth(200);
                        this.tweens.add({
                            targets: dmgNum,
                            y: targetY - 100,
                            alpha: 0,
                            scale: 1.8,
                            duration: 1200,
                            ease: 'Power2',
                            onComplete: () => dmgNum.destroy()
                        });

                        // === CONFETTI EXPLOSION on every hit ===
                        this.spawnConfetti(targetX, targetY, 40);

                        // Impact burst on the player
                        this.spawnImpact(targetX, targetY);

                        // Screen shake proportional to damage
                        this.cameras.main.shake(100 + dmg, 0.003 + dmg * 0.00005);

                        // Red vignette flash for big hits
                        if (dmg >= 50) {
                            this.spawnConfetti(targetX, targetY, 60);
                            const vignette = this.add.rectangle(
                                this.cameras.main.centerX, this.cameras.main.centerY,
                                this.scale.width, this.scale.height,
                                0xff0000, 0.15
                            ).setDepth(300).setScrollFactor(0);
                            this.tweens.add({
                                targets: vignette,
                                alpha: 0,
                                duration: 400,
                                onComplete: () => vignette.destroy()
                            });
                        }
                    }

                    // DEATH — massive explosion when a player dies
                    if (prevHp > 0 && p.hp <= 0) {
                        this.spawnExplosion(targetX, targetY);
                        this.spawnConfetti(targetX, targetY, 120);  // extra confetti rain
                        this.cameras.main.shake(300, 0.012);

                        // ELIMINATED text
                        const elimText = this.add.text(targetX, targetY, 'ELIMINATED', {
                            fontSize: '20px', color: '#ff6b6b', fontFamily: 'monospace',
                            stroke: '#000', strokeThickness: 5
                        }).setOrigin(0.5).setDepth(200);
                        this.tweens.add({
                            targets: elimText,
                            y: targetY - 60,
                            alpha: 0,
                            scale: 1.5,
                            duration: 1500,
                            ease: 'Power2',
                            onComplete: () => elimText.destroy()
                        });
                    }

                    this.playerLastHp.set(p.id, p.hp);
                    this.playerHpValues.set(p.id, p.hp);
                } else {
                    sprite.setVisible(false);
                    label.setVisible(false);
                }
            } else {
                // New player — custom avatar or Rick variant
                let sprite: Phaser.GameObjects.Sprite;
                if (avatar.custom) {
                    sprite = this.add.sprite(targetX, targetY, avatar.key);
                    sprite.setDisplaySize(this.tileSize, this.tileSize);
                    sprite.setDepth(10);
                } else {
                    sprite = this.add.sprite(targetX, targetY,
                        avatar.key, `${avatar.key}_down_1`);
                    sprite.setScale(0.5);
                    sprite.setDepth(10);
                }
                this.playerSprites.set(p.id, sprite);

                const label = this.add.text(targetX, targetY - 65, p.name, {
                    fontSize: '22px', color: '#ffe66d', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 5,
                    shadow: { offsetX: 2, offsetY: 2, color: '#000', blur: 4, fill: true }
                }).setOrigin(0.5).setDepth(61);
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

                // === MASSIVE EXPLOSION ===
                this.spawnExplosion(dx, dy);

                // Score popup — big and bold
                const scoreText = this.add.text(dx, dy - 15, '+10', {
                    fontSize: '22px', color: '#ffe66d', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 4
                }).setOrigin(0.5).setDepth(200);
                this.tweens.add({
                    targets: scoreText,
                    y: dy - 80,
                    alpha: 0,
                    scale: 1.5,
                    duration: 1000,
                    ease: 'Power2',
                    onComplete: () => scoreText.destroy()
                });

                // Death flash + shrink
                this.tweens.add({
                    targets: sprite,
                    alpha: 0,
                    scale: 0.05,
                    duration: 150,
                    onComplete: () => sprite.destroy()
                });
                this.enemySprites.delete(id);
            }
        }

        // Screen shake when enemies die — BIG and juicy
        if (killCount > 0) {
            this.cameras.main.shake(150, 0.006 * Math.min(killCount, 8));
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
            const px = this.arenaOffsetX + gx * ts;
            const py = this.arenaOffsetY + gy * ts;
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
            const px = this.arenaOffsetX + gx * this.tileSize;
            const py = this.arenaOffsetY + gy * this.tileSize;
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

    drawCrowd(): void {
        const arenaW = this.mapWidth * this.tileSize;
        const arenaH = this.mapHeight * this.tileSize;
        const ox = this.arenaOffsetX;  // arena pixel origin
        const oy = this.arenaOffsetY;
        const margin = 8;  // small gap between arena edge and crowd
        const skinColors = [0xf4c7a3, 0xe8b98d, 0xd4956b, 0xc68642, 0x8d5524, 0xffdbac];
        const shirtColors = [0xff6b6b, 0x4ecdc4, 0xffe66d, 0xff8844, 0x44aaff, 0xaa44ff,
                             0x66ff66, 0xff44aa, 0x44ffcc, 0xffaa44, 0xff3399, 0x3399ff];

        // South Park style characters — big round heads, tiny rectangle bodies
        const hatColors = [0xff0000, 0x0066ff, 0x00cc44, 0xff8800, 0xffee00,
                           0xff44cc, 0x8844ff, 0x00cccc, 0xcc0000, 0x884400];

        const drawPerson = (x: number, y: number, facing: string) => {
            const skin = skinColors[Math.floor(Math.random() * skinColors.length)];
            const shirt = shirtColors[Math.floor(Math.random() * shirtColors.length)];
            const hat = hatColors[Math.floor(Math.random() * hatColors.length)];

            // South Park proportions: BIG round head, small body — 2x scale
            const headR = 10 + Math.random() * 4;  // big head
            const bodyW = headR * 1.4;
            const bodyH = headR * 1.0;

            // Body — flat rectangle (construction paper style)
            const body = this.add.rectangle(x, y + headR * 0.3, bodyW, bodyH, shirt, 0.95).setDepth(-1);

            // Big round head
            const head = this.add.circle(x, y - headR * 0.6, headR, skin, 0.95).setDepth(-1);

            // Eyes — two dots (South Park classic)
            const eyeOff = headR * 0.25;
            const eyeR = headR * 0.18;
            const eyeWhiteL = this.add.circle(x - eyeOff, y - headR * 0.7, eyeR + 1, 0xffffff, 1).setDepth(-1);
            const eyeWhiteR = this.add.circle(x + eyeOff, y - headR * 0.7, eyeR + 1, 0xffffff, 1).setDepth(-1);
            const eyeL = this.add.circle(x - eyeOff, y - headR * 0.7, eyeR, 0x000000, 1).setDepth(-1);
            const eyeR2 = this.add.circle(x + eyeOff, y - headR * 0.7, eyeR, 0x000000, 1).setDepth(-1);

            // Hat/beanie (50% chance)
            let hatObj: Phaser.GameObjects.Shape | null = null;
            if (Math.random() < 0.5) {
                hatObj = this.add.rectangle(x, y - headR * 1.3, headR * 1.8, headR * 0.6, hat, 0.95).setDepth(-1);
            }

            const allParts = [body, head, eyeWhiteL, eyeWhiteR, eyeL, eyeR2];
            if (hatObj) allParts.push(hatObj);

            // Cheering animation — bounce up and down
            if (Math.random() < 0.45) {
                const delay = Math.random() * 3000;
                const dur = 300 + Math.random() * 500;
                this.tweens.add({
                    targets: allParts,
                    y: `-=${2 + Math.random() * 5}`,
                    duration: dur,
                    delay: delay,
                    yoyo: true,
                    repeat: -1,
                    ease: 'Sine.easeInOut'
                });
            }

            // Arms waving (some people throw hands up)
            if (Math.random() < 0.2) {
                const armL = this.add.rectangle(x - bodyW * 0.7, y - headR * 0.2, 2, headR * 0.8, skin, 0.9).setDepth(-1);
                const armR = this.add.rectangle(x + bodyW * 0.7, y - headR * 0.2, 2, headR * 0.8, skin, 0.9).setDepth(-1);
                this.tweens.add({
                    targets: [armL, armR],
                    angle: { from: -30, to: 30 },
                    duration: 400 + Math.random() * 400,
                    delay: Math.random() * 2000,
                    yoyo: true,
                    repeat: -1,
                    ease: 'Sine.easeInOut'
                });
            }

            // Signs/foam fingers (some fans)
            if (Math.random() < 0.12) {
                const signColor = shirtColors[Math.floor(Math.random() * shirtColors.length)];
                const sign = this.add.rectangle(x + (Math.random()-0.5)*10, y - headR * 2.2,
                    10 + Math.random()*8, 6 + Math.random()*4, signColor, 0.85).setDepth(-1);
                this.tweens.add({
                    targets: sign,
                    angle: { from: -20, to: 20 },
                    duration: 500 + Math.random() * 500,
                    delay: Math.random() * 1000,
                    yoyo: true,
                    repeat: -1,
                    ease: 'Sine.easeInOut'
                });
            }

            this.crowdSprites.push(body, head);
        };

        // Fill the ENTIRE border uniformly — full canvas width/height, no gaps
        const spacing = 22;
        const canvasW = this.scale.width;
        const canvasH = this.scale.height;

        // Top section — full width, all rows from y=0 to arena top
        for (let y = 15; y < oy - 5; y += spacing) {
            for (let x = 15; x < canvasW - 10; x += spacing) {
                drawPerson(x + (Math.random()-0.5)*8, y + (Math.random()-0.5)*8, 'down');
            }
        }

        // Bottom section — full width, all rows from arena bottom to canvas bottom
        for (let y = oy + arenaH + 10; y < canvasH - 5; y += spacing) {
            for (let x = 15; x < canvasW - 10; x += spacing) {
                drawPerson(x + (Math.random()-0.5)*8, y + (Math.random()-0.5)*8, 'up');
            }
        }

        // Left section — from arena top to arena bottom (middle band only, top/bottom already covered)
        for (let x = 15; x < ox - 5; x += spacing) {
            for (let y = oy; y < oy + arenaH; y += spacing) {
                drawPerson(x + (Math.random()-0.5)*8, y + (Math.random()-0.5)*8, 'right');
            }
        }

        // Right section — from arena top to arena bottom
        for (let x = ox + arenaW + 10; x < canvasW - 5; x += spacing) {
            for (let y = oy; y < oy + arenaH; y += spacing) {
                drawPerson(x + (Math.random()-0.5)*8, y + (Math.random()-0.5)*8, 'left');
            }
        }
    }

    spawnConfetti(x: number, y: number, count: number): void {
        const confettiColors = [
            0xff6b6b, 0xffe66d, 0x4ecdc4, 0xff8844, 0xffffff,
            0xff44ff, 0x44ff44, 0x44aaff, 0xffaa00, 0xff0066,
            0x00ffcc, 0xaa44ff, 0xff6600, 0x66ff66, 0xff3399
        ];
        for (let i = 0; i < count; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = 40 + Math.random() * 160;
            const size = 2 + Math.random() * 5;
            const color = confettiColors[Math.floor(Math.random() * confettiColors.length)];
            // Mix of circles and rectangles for variety
            let particle: Phaser.GameObjects.Shape;
            if (Math.random() > 0.5) {
                particle = this.add.circle(x, y, size, color, 1);
            } else {
                const w = 2 + Math.random() * 8;
                const h = 2 + Math.random() * 4;
                particle = this.add.rectangle(x, y, w, h, color, 1);
                (particle as Phaser.GameObjects.Rectangle).setAngle(Math.random() * 360);
            }
            particle.setDepth(250);
            const tx = x + Math.cos(angle) * speed;
            const ty = y + Math.sin(angle) * speed - Math.random() * 40; // bias upward
            this.tweens.add({
                targets: particle,
                x: tx,
                y: ty + 30 + Math.random() * 40, // gravity drop
                alpha: 0,
                scale: 0.1 + Math.random() * 0.3,
                angle: (particle as any).angle + (Math.random() - 0.5) * 720,
                duration: 600 + Math.random() * 800,
                ease: 'Power1',
                onComplete: () => particle.destroy()
            });
        }
    }

    spawnExplosion(x: number, y: number): void {
        // === MUSHROOM CLOUD EXPLOSION ===

        // 1. Big white flash
        const flash = this.add.circle(x, y, 8, 0xffffff, 1).setDepth(150);
        this.tweens.add({
            targets: flash,
            scale: 6,
            alpha: 0,
            duration: 300,
            ease: 'Power2',
            onComplete: () => flash.destroy()
        });

        // 2. Orange fireball expanding
        const fireball = this.add.circle(x, y, 6, 0xff6600, 0.9).setDepth(149);
        this.tweens.add({
            targets: fireball,
            scale: 5,
            alpha: 0,
            duration: 500,
            ease: 'Sine.easeOut',
            onComplete: () => fireball.destroy()
        });

        // 3. Red inner blast
        const blast = this.add.circle(x, y, 4, 0xff2200, 0.8).setDepth(148);
        this.tweens.add({
            targets: blast,
            scale: 4,
            alpha: 0,
            duration: 400,
            delay: 50,
            ease: 'Power1',
            onComplete: () => blast.destroy()
        });

        // 4. Expanding shockwave ring
        const ring = this.add.graphics().setDepth(147);
        ring.lineStyle(3, 0xffe66d, 0.8);
        ring.strokeCircle(x, y, 10);
        this.tweens.add({
            targets: ring,
            scale: 5,
            alpha: 0,
            duration: 600,
            ease: 'Power2',
            onComplete: () => ring.destroy()
        });

        // 5. Second slower shockwave
        const ring2 = this.add.graphics().setDepth(146);
        ring2.lineStyle(2, 0xff8844, 0.5);
        ring2.strokeCircle(x, y, 8);
        this.tweens.add({
            targets: ring2,
            scale: 7,
            alpha: 0,
            duration: 800,
            delay: 100,
            ease: 'Power1',
            onComplete: () => ring2.destroy()
        });

        // 6. MASSIVE confetti explosion — 80 particles on death
        this.spawnConfetti(x, y, 80);

        // 7. Smoke puffs rising up — BIG mushroom cloud
        for (let i = 0; i < 6; i++) {
            const ox = x + (Math.random() - 0.5) * 30;
            const smoke = this.add.circle(ox, y, 12 + Math.random() * 10, 0x444444, 0.5).setDepth(145);
            this.tweens.add({
                targets: smoke,
                y: y - 60 - Math.random() * 60,
                x: ox + (Math.random() - 0.5) * 40,
                scale: 3 + Math.random() * 2,
                alpha: 0,
                duration: 1000 + Math.random() * 600,
                delay: 80 + i * 60,
                ease: 'Sine.easeOut',
                onComplete: () => smoke.destroy()
            });
        }
    }

    spawnImpact(x: number, y: number): void {
        // Smaller explosion for bullet impacts

        // Flash
        const flash = this.add.circle(x, y, 6, 0xffffff, 0.9).setDepth(150);
        this.tweens.add({
            targets: flash,
            scale: 3,
            alpha: 0,
            duration: 200,
            onComplete: () => flash.destroy()
        });

        // Orange burst
        const burst = this.add.circle(x, y, 5, 0xff6600, 0.8).setDepth(149);
        this.tweens.add({
            targets: burst,
            scale: 2.5,
            alpha: 0,
            duration: 300,
            onComplete: () => burst.destroy()
        });

        // Sparks flying out
        const colors = [0xff6b6b, 0xffe66d, 0xff8844, 0xffffff];
        for (let i = 0; i < 8; i++) {
            const angle = (Math.PI * 2 * i) / 8 + (Math.random() - 0.5) * 0.5;
            const speed = 30 + Math.random() * 50;
            const color = colors[Math.floor(Math.random() * colors.length)];
            const spark = this.add.circle(x, y, 1.5 + Math.random() * 2, color, 1).setDepth(151);
            this.tweens.add({
                targets: spark,
                x: x + Math.cos(angle) * speed,
                y: y + Math.sin(angle) * speed,
                alpha: 0,
                duration: 300 + Math.random() * 200,
                ease: 'Power2',
                onComplete: () => spark.destroy()
            });
        }

        // Shockwave ring
        const ring = this.add.graphics().setDepth(147);
        ring.lineStyle(2, 0xffaa44, 0.7);
        ring.strokeCircle(x, y, 6);
        this.tweens.add({
            targets: ring,
            scale: 3,
            alpha: 0,
            duration: 400,
            onComplete: () => ring.destroy()
        });
    }

    update(): void {
        // HP bars follow sprite positions every frame (smooth during tweens)
        this.drawAllHpBars();
    }
}
