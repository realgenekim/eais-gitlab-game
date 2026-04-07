// THE GAME ITSELF
// Based on Emanuele Feronato's Phaser VS prototype
// https://emanueleferonato.com/2024/11/29/quick-html5-prototype-of-vampire-survivors-built-with-phaser-like-the-original-game/
//
// This starts as a standalone VS clone for MVP 1.
// MVP 2 will replace local game logic with server state via WebSocket.

import { GameOptions } from '../gameOptions';

export class PlayGame extends Phaser.Scene {

    constructor() {
        super({ key : 'PlayGame' });
    }

    controlKeys     : any;
    player          : Phaser.Types.Physics.Arcade.SpriteWithDynamicBody;
    enemyGroup      : Phaser.Physics.Arcade.Group;
    coinGroup        : Phaser.Physics.Arcade.Group;

    create() : void {

        // Player, enemies, coins, bullets
        this.player = this.physics.add.sprite(
            GameOptions.gameSize.width / 2,
            GameOptions.gameSize.height / 2,
            'player'
        );
        this.enemyGroup = this.physics.add.group();
        this.coinGroup = this.physics.add.group();
        const bulletGroup : Phaser.Physics.Arcade.Group = this.physics.add.group();

        // Keyboard controls (for local testing; bots use REST API)
        const keyboard = this.input.keyboard as Phaser.Input.Keyboard.KeyboardPlugin;
        this.controlKeys = keyboard.addKeys({
            'up'    : Phaser.Input.Keyboard.KeyCodes.W,
            'left'  : Phaser.Input.Keyboard.KeyCodes.A,
            'down'  : Phaser.Input.Keyboard.KeyCodes.S,
            'right' : Phaser.Input.Keyboard.KeyCodes.D
        });

        // Enemy spawn zone — ring around the visible area
        const outerRect = new Phaser.Geom.Rectangle(
            -100, -100,
            GameOptions.gameSize.width + 200, GameOptions.gameSize.height + 200
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
                const enemy = this.physics.add.sprite(pt.x, pt.y, 'enemy');
                this.enemyGroup.add(enemy);
            },
        });

        // Fire bullets at closest enemy (auto-fire for now; MVP 4 switches to manual targeting)
        this.time.addEvent({
            delay    : GameOptions.bulletRate,
            loop     : true,
            callback : () => {
                const closest : any = this.physics.closest(
                    this.player,
                    this.enemyGroup.getMatching('visible', true)
                );
                if (closest != null) {
                    const bullet = this.physics.add.sprite(this.player.x, this.player.y, 'bullet');
                    bulletGroup.add(bullet);
                    this.physics.moveToObject(bullet, closest, GameOptions.bulletSpeed);
                }
            },
        });

        // Bullet hits enemy → drop coin, kill both
        this.physics.add.collider(bulletGroup, this.enemyGroup, (bullet : any, enemy : any) => {
            const coin = this.physics.add.sprite(enemy.x, enemy.y, 'coin');
            this.coinGroup.add(coin);
            bulletGroup.killAndHide(bullet);
            bullet.body.checkCollision.none = true;
            this.enemyGroup.killAndHide(enemy);
            enemy.body.checkCollision.none = true;
        });

        // Enemy touches player → death
        this.physics.add.collider(this.player, this.enemyGroup, () => {
            this.scene.restart();
        });

        // Player touches coin → collect
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
        if (dir.x == 0 || dir.y == 0) {
            this.player.setVelocity(dir.x * GameOptions.playerSpeed, dir.y * GameOptions.playerSpeed);
        } else {
            // Normalize diagonal movement
            this.player.setVelocity(
                dir.x * GameOptions.playerSpeed / Math.sqrt(2),
                dir.y * GameOptions.playerSpeed / Math.sqrt(2)
            );
        }

        // Magnet: attract nearby coins
        const nearby = this.physics.overlapCirc(
            this.player.x, this.player.y,
            GameOptions.magnetRadius, true, true
        ) as Phaser.Physics.Arcade.Body[];
        nearby.forEach((body : any) => {
            const sprite = body.gameObject as Phaser.Physics.Arcade.Sprite;
            if (sprite.texture.key == 'coin') {
                this.physics.moveToObject(sprite, this.player, 500);
            }
        });

        // Enemies swarm toward player
        this.enemyGroup.getMatching('visible', true).forEach((enemy : any) => {
            this.physics.moveToObject(enemy, this.player, GameOptions.enemySpeed);
        });
    }
}
