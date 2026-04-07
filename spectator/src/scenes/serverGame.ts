// SERVER-CONNECTED GAME (MVP 2)
// Receives game state from Clojure server via WebSocket.
// Players rendered from server state. Enemies local (until MVP 3).

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

interface ServerState {
    type: string;
    tick: number;
    players: ServerPlayer[];
    map: { width: number; height: number };
}

export class ServerGame extends Phaser.Scene {

    constructor() {
        super({ key: 'ServerGame' });
    }

    ws              : WebSocket | null = null;
    connected       : boolean = false;
    lastState       : ServerState | null = null;
    playerSprites   : Map<string, Phaser.GameObjects.Sprite> = new Map();
    playerLabels    : Map<string, Phaser.GameObjects.Text> = new Map();
    enemyGroup      : Phaser.Physics.Arcade.Group;
    coinGroup       : Phaser.Physics.Arcade.Group;
    bulletGroup     : Phaser.Physics.Arcade.Group;
    killCount       : number = 0;
    killText        : Phaser.GameObjects.Text;
    statusText      : Phaser.GameObjects.Text;
    tileSize        : number = 48;  // pixels per grid cell (fits 21x19 in ~1000x900)
    mapWidth        : number = 21;
    mapHeight       : number = 19;

    create(): void {
        // Groups for local enemies
        this.enemyGroup = this.physics.add.group();
        this.coinGroup = this.physics.add.group();
        this.bulletGroup = this.physics.add.group();

        // HUD
        this.killCount = 0;
        this.killText = this.add.text(16, 16, 'Kills: 0', {
            fontSize: '24px', color: '#ff6b6b', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 3
        }).setDepth(100);

        this.statusText = this.add.text(16, 48, 'Connecting...', {
            fontSize: '16px', color: '#4ecdc4', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2
        }).setDepth(100);

        // Connect to server
        this.connectWebSocket();

        // Local enemy spawning (temporary — moves to server in MVP 3)
        this.spawnLocalEnemies();
    }

    connectWebSocket(): void {
        const url = GameOptions.serverWsUrl || 'ws://localhost:33333/spectate-ws';
        console.log('Connecting to', url);
        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
            this.connected = true;
            this.statusText.setText('Connected to server');
            console.log('WebSocket connected');
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

        this.ws.onerror = () => {
            console.error('WebSocket error');
        };
    }

    gridToPixel(gx: number, gy: number): [number, number] {
        return [gx * this.tileSize + this.tileSize / 2,
                gy * this.tileSize + this.tileSize / 2];
    }

    applyServerState(state: ServerState): void {
        this.lastState = state;

        // Update map size on first state
        if (state.map) {
            this.mapWidth = state.map.width;
            this.mapHeight = state.map.height;
        }

        this.statusText.setText(
            `Tick ${state.tick} | ${state.players.length} players | WS connected`);

        // Dispatch to HTML scoreboard overlay
        window.dispatchEvent(new CustomEvent('gameState', { detail: state }));

        const seenIds = new Set<string>();

        for (const p of state.players) {
            seenIds.add(p.id);
            const [targetX, targetY] = this.gridToPixel(p.x, p.y);

            if (this.playerSprites.has(p.id)) {
                // Existing player — move to new position
                const sprite = this.playerSprites.get(p.id)!;
                const label = this.playerLabels.get(p.id)!;

                if (p.alive) {
                    sprite.setVisible(true);
                    label.setVisible(true);

                    // Kill existing tweens for this sprite to prevent stacking
                    this.tweens.killTweensOf(sprite);
                    this.tweens.killTweensOf(label);

                    // Direction for animation
                    const dx = targetX - sprite.x;
                    const dy = targetY - sprite.y;

                    // Tween to new position
                    this.tweens.add({
                        targets: sprite,
                        x: targetX,
                        y: targetY,
                        duration: GameOptions.tickMs || 250,
                        ease: 'Linear'
                    });
                    this.tweens.add({
                        targets: label,
                        x: targetX,
                        y: targetY - 40,
                        duration: GameOptions.tickMs || 250,
                        ease: 'Linear'
                    });

                    // Walk animation
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
                // New player — create sprite (use add.sprite, NOT physics)
                const sprite = this.add.sprite(targetX, targetY,
                    'RickDefault', 'RickDefault_front');
                sprite.setScale(0.5);
                sprite.setDepth(10);
                this.playerSprites.set(p.id, sprite);

                const label = this.add.text(targetX, targetY - 40, p.name, {
                    fontSize: '12px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(50);
                this.playerLabels.set(p.id, label);

                console.log(`Created sprite for ${p.name} at (${p.x},${p.y}) → pixel (${targetX},${targetY})`);

                if (!p.alive) {
                    sprite.setVisible(false);
                    label.setVisible(false);
                }
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

    spawnLocalEnemies(): void {
        const w = this.mapWidth * this.tileSize;
        const h = this.mapHeight * this.tileSize;
        const outerRect = new Phaser.Geom.Rectangle(-150, -150, w + 300, h + 300);
        const innerRect = new Phaser.Geom.Rectangle(-50, -50, w + 100, h + 100);

        this.time.addEvent({
            delay: GameOptions.enemyRate,
            loop: true,
            callback: () => {
                const pt = Phaser.Geom.Rectangle.RandomOutside(outerRect, innerRect);
                const enemy = this.physics.add.sprite(pt.x, pt.y, 'FloopyDoops', 'FloopyDoops_down_1');
                enemy.setScale(0.3);
                enemy.body.setSize(60, 80);
                enemy.play('floopy-walk-down');
                this.enemyGroup.add(enemy);
            },
        });

        // Auto-fire from nearest player
        this.time.addEvent({
            delay: GameOptions.bulletRate,
            loop: true,
            callback: () => {
                let shooter: Phaser.GameObjects.Sprite | null = null;
                for (const [, s] of this.playerSprites) {
                    if (s.visible) { shooter = s; break; }
                }
                if (!shooter) return;

                const enemies = this.enemyGroup.getMatching('visible', true);
                const closest: any = this.physics.closest(shooter as any, enemies);
                if (closest) {
                    const bullet = this.physics.add.sprite(
                        shooter.x, shooter.y, 'Bullets', 'blaster1');
                    bullet.setScale(0.5);
                    this.bulletGroup.add(bullet);
                    this.physics.moveToObject(bullet, closest, GameOptions.bulletSpeed);
                }
            },
        });

        // Bullet hits enemy
        this.physics.add.collider(this.bulletGroup, this.enemyGroup, (bullet: any, enemy: any) => {
            const gem = this.physics.add.sprite(enemy.x, enemy.y, 'game-items', 'ExpGem');
            gem.setScale(1.5);
            this.coinGroup.add(gem);
            this.bulletGroup.killAndHide(bullet);
            bullet.body.checkCollision.none = true;
            this.enemyGroup.killAndHide(enemy);
            enemy.body.checkCollision.none = true;
            this.killCount++;
            this.killText.setText('Kills: ' + this.killCount);
        });
    }

    update(): void {
        // Move enemies toward nearest visible player
        let target: Phaser.GameObjects.Sprite | null = null;
        for (const [, s] of this.playerSprites) {
            if (s.visible) { target = s; break; }
        }

        if (target) {
            const t = target;
            this.enemyGroup.getMatching('visible', true).forEach((enemy: any) => {
                this.physics.moveToObject(enemy, t, GameOptions.enemySpeed);
                const dx = t.x - enemy.x;
                const dy = t.y - enemy.y;
                if (Math.abs(dy) > Math.abs(dx)) {
                    enemy.play(dy > 0 ? 'floopy-walk-down' : 'floopy-walk-up', true);
                    enemy.setFlipX(false);
                } else {
                    enemy.play('floopy-walk-side', true);
                    enemy.setFlipX(dx < 0);
                }
            });

            // Magnet XP gems
            const nearby = this.physics.overlapCirc(
                t.x, t.y, GameOptions.magnetRadius, true, true
            ) as Phaser.Physics.Arcade.Body[];
            nearby.forEach((body: any) => {
                const s = body.gameObject as Phaser.Physics.Arcade.Sprite;
                if (s.texture.key === 'game-items') {
                    this.physics.moveToObject(s, t, 500);
                }
            });
        }
    }
}
