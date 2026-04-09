// CLASS TO PRELOAD ASSETS
// Loads rick-survival sprite sheets for players, enemies, bullets, items

// All available Rick variants
const RICK_VARIANTS = [
    'RickDefault', 'PickleRick', 'CrowRick', 'RickNinja',
    'RickMiami', 'TinyRick', 'RickWarrior', 'RickRobot'
];

// Enemy types
const ENEMY_TYPES = ['FloopyDoops', 'Squanchy'];

export { RICK_VARIANTS };

export class PreloadAssets extends Phaser.Scene {

    constructor() {
        super({ key: 'PreloadAssets' });
    }

    preload(): void {
        // Load all Rick variants
        for (const rick of RICK_VARIANTS) {
            this.load.atlas(rick,
                `assets/spritesheets/players/${rick}.png`,
                `assets/spritesheets/players/${rick}.json`);
        }

        // Enemy sprite sheets
        for (const enemy of ENEMY_TYPES) {
            this.load.atlas(enemy,
                `assets/spritesheets/enemies/${enemy}.png`,
                `assets/spritesheets/enemies/${enemy}.json`);
        }

        // Bullets
        this.load.atlas('Bullets',
            'assets/spritesheets/bullets/Bullets.png',
            'assets/spritesheets/bullets/Bullets.json');

        // Items (coins, XP gems, health)
        this.load.atlas('game-items',
            'assets/spritesheets/items/game_items.png',
            'assets/spritesheets/items/game_items_atlas.json');

        // IT Revolution logo for floor watermark
        this.load.svg('itrev-logo', 'it-rev-logo.svg', { width: 400, height: 200 });
    }

    create(): void {
        // Create walk animations for each Rick variant
        for (const rick of RICK_VARIANTS) {
            const prefix = rick.toLowerCase().replace(/[^a-z0-9]/g, '');

            this.anims.create({
                key: `${prefix}-walk-down`,
                frames: [
                    { key: rick, frame: `${rick}_down_1` },
                    { key: rick, frame: `${rick}_down_2` },
                    { key: rick, frame: `${rick}_down_3` },
                    { key: rick, frame: `${rick}_down_4` },
                ],
                frameRate: 8, repeat: -1
            });
            this.anims.create({
                key: `${prefix}-walk-up`,
                frames: [
                    { key: rick, frame: `${rick}_up_1` },
                    { key: rick, frame: `${rick}_up_2` },
                    { key: rick, frame: `${rick}_up_3` },
                    { key: rick, frame: `${rick}_up_4` },
                ],
                frameRate: 8, repeat: -1
            });
            this.anims.create({
                key: `${prefix}-walk-side`,
                frames: [
                    { key: rick, frame: `${rick}_side_1` },
                    { key: rick, frame: `${rick}_side_2` },
                    { key: rick, frame: `${rick}_side_3` },
                    { key: rick, frame: `${rick}_side_4` },
                ],
                frameRate: 8, repeat: -1
            });
            this.anims.create({
                key: `${prefix}-idle`,
                frames: [{ key: rick, frame: `${rick}_down_1` }],
                frameRate: 1, repeat: 0
            });
        }

        // Enemy walk animations
        for (const enemy of ENEMY_TYPES) {
            const prefix = enemy.toLowerCase().replace(/[^a-z0-9]/g, '');

            this.anims.create({
                key: `${prefix}-walk-down`,
                frames: [
                    { key: enemy, frame: `${enemy}_down_1` },
                    { key: enemy, frame: `${enemy}_down_2` },
                    { key: enemy, frame: `${enemy}_down_3` },
                    { key: enemy, frame: `${enemy}_down_4` },
                ],
                frameRate: 8, repeat: -1
            });
            this.anims.create({
                key: `${prefix}-walk-up`,
                frames: [
                    { key: enemy, frame: `${enemy}_up_1` },
                    { key: enemy, frame: `${enemy}_up_2` },
                    { key: enemy, frame: `${enemy}_up_3` },
                    { key: enemy, frame: `${enemy}_up_4` },
                ],
                frameRate: 8, repeat: -1
            });
            this.anims.create({
                key: `${prefix}-walk-side`,
                frames: [
                    { key: enemy, frame: `${enemy}_side_1` },
                    { key: enemy, frame: `${enemy}_side_2` },
                    { key: enemy, frame: `${enemy}_side_3` },
                    { key: enemy, frame: `${enemy}_side_4` },
                ],
                frameRate: 8, repeat: -1
            });
        }

        // Route to correct scene based on URL
        const useServer = window.location.search.includes('server');
        this.scene.start(useServer ? 'ServerGame' : 'PlayGame');
    }
}
