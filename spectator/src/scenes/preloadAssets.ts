// CLASS TO PRELOAD ASSETS
// Loads rick-survival sprite sheets for players, enemies, bullets, items

export class PreloadAssets extends Phaser.Scene {

    constructor() {
        super({ key : 'PreloadAssets' });
    }

    preload() : void {
        // Player sprite sheets (rick-survival TexturePacker atlases)
        this.load.atlas('RickDefault',
            'assets/spritesheets/players/RickDefault.png',
            'assets/spritesheets/players/RickDefault.json');

        // Enemy sprite sheets
        this.load.atlas('FloopyDoops',
            'assets/spritesheets/enemies/FloopyDoops.png',
            'assets/spritesheets/enemies/FloopyDoops.json');
        this.load.atlas('Squanchy',
            'assets/spritesheets/enemies/Squanchy.png',
            'assets/spritesheets/enemies/Squanchy.json');

        // Bullets
        this.load.atlas('Bullets',
            'assets/spritesheets/bullets/Bullets.png',
            'assets/spritesheets/bullets/Bullets.json');

        // Items (coins, XP gems, health)
        this.load.atlas('game-items',
            'assets/spritesheets/items/game_items.png',
            'assets/spritesheets/items/game_items_atlas.json');
    }

    create() : void {
        // Create walk animations for RickDefault
        this.anims.create({
            key: 'rick-walk-down',
            frames: [
                { key: 'RickDefault', frame: 'RickDefault_down_1' },
                { key: 'RickDefault', frame: 'RickDefault_down_2' },
                { key: 'RickDefault', frame: 'RickDefault_down_3' },
                { key: 'RickDefault', frame: 'RickDefault_down_4' },
            ],
            frameRate: 8,
            repeat: -1
        });
        this.anims.create({
            key: 'rick-walk-up',
            frames: [
                { key: 'RickDefault', frame: 'RickDefault_up_1' },
                { key: 'RickDefault', frame: 'RickDefault_up_2' },
                { key: 'RickDefault', frame: 'RickDefault_up_3' },
                { key: 'RickDefault', frame: 'RickDefault_up_4' },
            ],
            frameRate: 8,
            repeat: -1
        });
        this.anims.create({
            key: 'rick-walk-side',
            frames: [
                { key: 'RickDefault', frame: 'RickDefault_side_1' },
                { key: 'RickDefault', frame: 'RickDefault_side_2' },
                { key: 'RickDefault', frame: 'RickDefault_side_3' },
                { key: 'RickDefault', frame: 'RickDefault_side_4' },
            ],
            frameRate: 8,
            repeat: -1
        });
        this.anims.create({
            key: 'rick-idle',
            frames: [{ key: 'RickDefault', frame: 'RickDefault_front' }],
            frameRate: 1,
            repeat: 0
        });

        // FloopyDoops walk animations
        this.anims.create({
            key: 'floopy-walk-down',
            frames: [
                { key: 'FloopyDoops', frame: 'FloopyDoops_down_1' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_down_2' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_down_3' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_down_4' },
            ],
            frameRate: 8,
            repeat: -1
        });
        this.anims.create({
            key: 'floopy-walk-up',
            frames: [
                { key: 'FloopyDoops', frame: 'FloopyDoops_up_1' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_up_2' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_up_3' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_up_4' },
            ],
            frameRate: 8,
            repeat: -1
        });
        this.anims.create({
            key: 'floopy-walk-side',
            frames: [
                { key: 'FloopyDoops', frame: 'FloopyDoops_side_1' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_side_2' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_side_3' },
                { key: 'FloopyDoops', frame: 'FloopyDoops_side_4' },
            ],
            frameRate: 8,
            repeat: -1
        });

        this.scene.start('PlayGame');
    }
}
