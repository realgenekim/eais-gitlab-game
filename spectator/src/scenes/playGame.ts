// THE GAME ITSELF
// Based on Emanuele Feronato's Phaser VS prototype
// https://emanueleferonato.com/2024/11/29/quick-html5-prototype-of-vampire-survivors-built-with-phaser-like-the-original-game/
//
// MVP 1: Standalone VS clone using rick-survival sprites.
// MVP 2 will replace local game logic with server state via WebSocket.

import { GameOptions } from '../gameOptions';

export class PlayGame extends Phaser.Scene {

    constructor() {
        super({ key : 'PlayGame' });
    }

    controlKeys     : any;
    player          : Phaser.Physics.Arcade.Sprite;
    enemyGroup      : Phaser.Physics.Arcade.Group;
    coinGroup       : Phaser.Physics.Arcade.Group;
    bulletGroup     : Phaser.Physics.Arcade.Group;
    killCount       : number = 0;
    killText        : Phaser.GameObjects.Text;

    create() : void {

        // Player — RickDefault sprite, scaled down for the arena
        this.player = this.physics.add.sprite(
            GameOptions.gameSize.width / 2,
            GameOptions.gameSize.height / 2,
            'RickDefault',
            'RickDefault_front'
        );
        this.player.setScale(0.4);
        this.player.setCollideWorldBounds(true);
        this.player.body.setSize(60, 100);

        // Groups
        this.enemyGroup = this.physics.add.group();
        this.coinGroup = this.physics.add.group();
        this.bulletGroup = this.physics.add.group();

        // Kill counter HUD
        this.killCount = 0;
        this.killText = this.add.text(16, 16, 'Kills: 0', {
            fontSize: '24px',
            color: '#ff6b6b',
            fontFamily: 'monospace',
            stroke: '#000',
            strokeThickness: 3
        }).setScrollFactor(0).setDepth(100);

        // Keyboard controls
        const keyboard = this.input.keyboard as Phaser.Input.Keyboard.KeyboardPlugin;
        this.controlKeys = keyboard.addKeys({
            'up'    : Phaser.Input.Keyboard.KeyCodes.W,
            'left'  : Phaser.Input.Keyboard.KeyCodes.A,
            'down'  : Phaser.Input.Keyboard.KeyCodes.S,
            'right' : Phaser.Input.Keyboard.KeyCodes.D
        });

        // Enemy spawn zone — ring outside visible area
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
            delay    : GameOptions.enemyRate,
            loop     : true,
            callback : () => {
                const pt = Phaser.Geom.Rectangle.RandomOutside(outerRect, innerRect);
                const enemy = this.physics.add.sprite(pt.x, pt.y, 'FloopyDoops', 'FloopyDoops_down_1');
                enemy.setScale(0.35);
                enemy.body.setSize(60, 80);
                enemy.play('floopy-walk-down');
                this.enemyGroup.add(enemy);
            },
        });

        // Fire bullets — auto-fire at closest enemy for now
        // MVP 4 switches to manual targeting from bot commands
        this.time.addEvent({
            delay    : GameOptions.bulletRate,
            loop     : true,
            callback : () => {
                const visibleEnemies = this.enemyGroup.getMatching('visible', true);
                const closest : any = this.physics.closest(this.player, visibleEnemies);
                if (closest != null) {
                    const bullet = this.physics.add.sprite(
                        this.player.x, this.player.y,
                        'Bullets', 'blaster1'
                    );
                    bullet.setScale(0.5);
                    this.bulletGroup.add(bullet);
                    this.physics.moveToObject(bullet, closest, GameOptions.bulletSpeed);
                }
            },
        });

        // Bullet hits enemy → drop XP gem, kill both
        this.physics.add.collider(this.bulletGroup, this.enemyGroup, (bullet : any, enemy : any) => {
            // Drop XP gem at enemy position
            const gem = this.physics.add.sprite(enemy.x, enemy.y, 'game-items', 'ExpGem');
            gem.setScale(1.5);
            this.coinGroup.add(gem);

            // Kill bullet
            this.bulletGroup.killAndHide(bullet);
            bullet.body.checkCollision.none = true;

            // Kill enemy
            this.enemyGroup.killAndHide(enemy);
            enemy.body.checkCollision.none = true;

            // Update kill count
            this.killCount++;
            this.killText.setText('Kills: ' + this.killCount);
        });

        // Enemy touches player → death
        this.physics.add.collider(this.player, this.enemyGroup, () => {
            this.scene.restart();
        });

        // Player touches gem → collect
        this.physics.add.collider(this.player, this.coinGroup, (player : any, coin : any) => {
            this.coinGroup.killAndHide(coin);
            coin.body.checkCollision.none = true;
        });
    }

    update() {
        // Movement from keyboard
        let dir = new Phaser.Math.Vector2(0, 0);
        if (this.controlKeys.right.isDown) dir.x++;
        if (this.controlKeys.left.isDown)  dir.x--;
        if (this.controlKeys.up.isDown)    dir.y--;
        if (this.controlKeys.down.isDown)  dir.y++;

        this.player.setVelocity(0, 0);
        const speed = GameOptions.playerSpeed;
        if (dir.x == 0 && dir.y == 0) {
            // Idle
            this.player.play('rick-idle', true);
        } else {
            // Normalize diagonal
            const factor = (dir.x != 0 && dir.y != 0) ? 1 / Math.sqrt(2) : 1;
            this.player.setVelocity(dir.x * speed * factor, dir.y * speed * factor);

            // Pick animation based on movement direction
            if (dir.y > 0) {
                this.player.play('rick-walk-down', true);
                this.player.setFlipX(false);
            } else if (dir.y < 0) {
                this.player.play('rick-walk-up', true);
                this.player.setFlipX(false);
            } else if (dir.x != 0) {
                this.player.play('rick-walk-side', true);
                this.player.setFlipX(dir.x < 0);
            }
        }

        // Magnet: attract nearby XP gems
        const nearby = this.physics.overlapCirc(
            this.player.x, this.player.y,
            GameOptions.magnetRadius, true, true
        ) as Phaser.Physics.Arcade.Body[];
        nearby.forEach((body : any) => {
            const sprite = body.gameObject as Phaser.Physics.Arcade.Sprite;
            if (sprite.texture.key == 'game-items') {
                this.physics.moveToObject(sprite, this.player, 500);
            }
        });

        // Enemies swarm toward player, pick animation based on direction
        this.enemyGroup.getMatching('visible', true).forEach((enemy : any) => {
            this.physics.moveToObject(enemy, this.player, GameOptions.enemySpeed);

            // Update enemy animation based on movement direction
            const dx = this.player.x - enemy.x;
            const dy = this.player.y - enemy.y;
            if (Math.abs(dy) > Math.abs(dx)) {
                if (dy > 0) {
                    enemy.play('floopy-walk-down', true);
                } else {
                    enemy.play('floopy-walk-up', true);
                }
                enemy.setFlipX(false);
            } else {
                enemy.play('floopy-walk-side', true);
                enemy.setFlipX(dx < 0);
            }
        });
    }
}
