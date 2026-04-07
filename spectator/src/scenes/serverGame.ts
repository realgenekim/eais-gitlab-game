// SERVER-CONNECTED GAME (MVP 2)
// Receives game state from Clojure server via WebSocket.
// Players are rendered from server state. Enemies are local (until MVP 3).

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
    playerSprites   : Map<string, Phaser.Physics.Arcade.Sprite> = new Map();
    playerLabels    : Map<string, Phaser.GameObjects.Text> = new Map();
    enemyGroup      : Phaser.Physics.Arcade.Group;
    coinGroup       : Phaser.Physics.Arcade.Group;
    bulletGroup     : Phaser.Physics.Arcade.Group;
    killCount       : number = 0;
    killText        : Phaser.GameObjects.Text;
    statusText      : Phaser.GameObjects.Text;
    tileSize        : number = 64;  // pixels per grid cell

    create(): void {
        // Groups for local enemies (until MVP 3 moves them server-side)
        this.enemyGroup = this.physics.add.group();
        this.coinGroup = this.physics.add.group();
        this.bulletGroup = this.physics.add.group();

        // HUD
        this.killCount = 0;
        this.killText = this.add.text(16, 16, 'Kills: 0', {
            fontSize: '24px', color: '#ff6b6b', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 3
        }).setScrollFactor(0).setDepth(100);

        this.statusText = this.add.text(16, 48, 'Connecting...', {
            fontSize: '16px', color: '#4ecdc4', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2
        }).setScrollFactor(0).setDepth(100);

        // Connect to server WebSocket
        this.connectWebSocket();

        // Local enemy spawning (temporary — moves to server in MVP 3)
        this.spawnLocalEnemies();
    }

    connectWebSocket(): void {
        const url = GameOptions.serverWsUrl || 'ws://localhost:33333/spectate-ws';
        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
            this.connected = true;
            this.statusText.setText('Connected to server');
            console.log('WebSocket connected to', url);
        };

        this.ws.onmessage = (event) => {
            try {
                const state: ServerState = JSON.parse(event.data);
                if (state.type === 'state') {
                    this.applyServerState(state);
                }
            } catch (e) {
                console.error('Failed to parse server state:', e);
            }
        };

        this.ws.onclose = () => {
            this.connected = false;
            this.statusText.setText('Disconnected — reconnecting...');
            // Reconnect after 2s
            this.time.delayedCall(2000, () => this.connectWebSocket());
        };

        this.ws.onerror = (err) => {
            console.error('WebSocket error:', err);
        };
    }

    applyServerState(state: ServerState): void {
        this.lastState = state;
        this.statusText.setText(`Tick ${state.tick} | ${state.players.length} players`);

        // Update or create player sprites
        const seenIds = new Set<string>();

        for (const p of state.players) {
            seenIds.add(p.id);
            const targetX = p.x * this.tileSize + this.tileSize / 2;
            const targetY = p.y * this.tileSize + this.tileSize / 2;

            if (this.playerSprites.has(p.id)) {
                // Existing player — tween to new position
                const sprite = this.playerSprites.get(p.id)!;
                const label = this.playerLabels.get(p.id)!;

                if (p.alive) {
                    sprite.setVisible(true);
                    label.setVisible(true);
                    // Smooth interpolation to server position
                    this.tweens.add({
                        targets: sprite,
                        x: targetX,
                        y: targetY,
                        duration: GameOptions.tickMs || 250,
                        ease: 'Linear'
                    });
                    // Label follows sprite
                    this.tweens.add({
                        targets: label,
                        x: targetX,
                        y: targetY - 50,
                        duration: GameOptions.tickMs || 250,
                        ease: 'Linear'
                    });
                    // Pick walk animation based on movement
                    const dx = targetX - sprite.x;
                    const dy = targetY - sprite.y;
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
                // New player — create sprite
                const sprite = this.physics.add.sprite(targetX, targetY,
                    'RickDefault', 'RickDefault_front');
                sprite.setScale(0.4);
                sprite.body.setSize(60, 100);
                this.playerSprites.set(p.id, sprite);

                const label = this.add.text(targetX, targetY - 50, p.name, {
                    fontSize: '14px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 3
                }).setOrigin(0.5).setDepth(50);
                this.playerLabels.set(p.id, label);

                // Set up bullet collision with enemies for this player's shots
                // (auto-fire from player position toward nearest enemy)
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
        // Enemy spawn zone
        const outerRect = new Phaser.Geom.Rectangle(
            -150, -150,
            GameOptions.gameSize.width + 300, GameOptions.gameSize.height + 300
        );
        const innerRect = new Phaser.Geom.Rectangle(
            -50, -50,
            GameOptions.gameSize.width + 100, GameOptions.gameSize.height + 100
        );

        // Spawn enemies on timer
        this.time.addEvent({
            delay: GameOptions.enemyRate,
            loop: true,
            callback: () => {
                const pt = Phaser.Geom.Rectangle.RandomOutside(outerRect, innerRect);
                const enemy = this.physics.add.sprite(pt.x, pt.y, 'FloopyDoops', 'FloopyDoops_down_1');
                enemy.setScale(0.35);
                enemy.body.setSize(60, 80);
                enemy.play('floopy-walk-down');
                this.enemyGroup.add(enemy);
            },
        });

        // Auto-fire bullets from first player toward nearest enemy
        this.time.addEvent({
            delay: GameOptions.bulletRate,
            loop: true,
            callback: () => {
                // Find first alive player sprite
                let shooter: Phaser.Physics.Arcade.Sprite | null = null;
                for (const [_id, sprite] of this.playerSprites) {
                    if (sprite.visible) { shooter = sprite; break; }
                }
                if (!shooter) return;

                const visibleEnemies = this.enemyGroup.getMatching('visible', true);
                const closest: any = this.physics.closest(shooter, visibleEnemies);
                if (closest) {
                    const bullet = this.physics.add.sprite(
                        shooter.x, shooter.y, 'Bullets', 'blaster1'
                    );
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

        // Enemy touches player → damage (don't restart, just log)
        for (const [_id, sprite] of this.playerSprites) {
            this.physics.add.collider(sprite, this.enemyGroup, () => {
                // In server mode, damage is handled server-side
                // For now just flash the sprite
            });
        }
    }

    update(): void {
        // Move enemies toward nearest visible player
        let targetSprite: Phaser.Physics.Arcade.Sprite | null = null;
        for (const [_id, sprite] of this.playerSprites) {
            if (sprite.visible) { targetSprite = sprite; break; }
        }

        if (targetSprite) {
            const target = targetSprite;
            this.enemyGroup.getMatching('visible', true).forEach((enemy: any) => {
                this.physics.moveToObject(enemy, target, GameOptions.enemySpeed);
                const dx = target.x - enemy.x;
                const dy = target.y - enemy.y;
                if (Math.abs(dy) > Math.abs(dx)) {
                    enemy.play(dy > 0 ? 'floopy-walk-down' : 'floopy-walk-up', true);
                    enemy.setFlipX(false);
                } else {
                    enemy.play('floopy-walk-side', true);
                    enemy.setFlipX(dx < 0);
                }
            });

            // Magnet: attract nearby XP gems toward nearest player
            const nearby = this.physics.overlapCirc(
                target.x, target.y,
                GameOptions.magnetRadius, true, true
            ) as Phaser.Physics.Arcade.Body[];
            nearby.forEach((body: any) => {
                const sprite = body.gameObject as Phaser.Physics.Arcade.Sprite;
                if (sprite.texture.key === 'game-items') {
                    this.physics.moveToObject(sprite, target, 500);
                }
            });
        }
    }
}
