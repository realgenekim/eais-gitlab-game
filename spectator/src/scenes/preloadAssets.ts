// CLASS TO PRELOAD ASSETS
// Loads rick-survival sprite sheets for players, enemies, bullets, items

export class PreloadAssets extends Phaser.Scene {

    constructor() {
        super({ key : 'PreloadAssets' });
    }

    preload() : void {
        // Placeholder sprites — replace with rick-survival sprite sheets in MVP 1
        this.load.image('enemy', 'assets/sprites/enemy.png');
        this.load.image('player', 'assets/sprites/player.png');
        this.load.image('bullet', 'assets/sprites/bullet.png');
        this.load.image('coin', 'assets/sprites/coin.png');

        // TODO MVP 1: Load rick-survival atlas sprites
        // this.load.atlas('RickDefault', 'assets/spritesheets/players/RickDefault.png',
        //                 'assets/spritesheets/players/RickDefault.json');
        // this.load.atlas('FloopyDoops', 'assets/spritesheets/enemies/FloopyDoops.png',
        //                 'assets/spritesheets/enemies/FloopyDoops.json');
    }

    create() : void {
        this.scene.start('PlayGame');
    }
}
