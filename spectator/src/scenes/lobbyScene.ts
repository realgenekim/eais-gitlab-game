// LOBBY SCENE — Fortnite-style gear room
// Connects to Python lobby server via WebSocket.
// Shows your bot's character with equipped gear, stats, and budget.
// Auto-updates when loadout.py is saved.

import { RICK_VARIANTS } from './preloadAssets';

interface GearItem {
    key: string;
    slot: string;
    cost: number;
    effects: Record<string, number | boolean>;
}

interface LoadoutUpdate {
    type: 'loadout-update';
    valid: boolean;
    errors: string[];
    name: string;
    avatar: string;
    avatar_image?: string;  // data URL for custom avatar
    cost: number;
    budget: number;
    stats: Record<string, number | boolean>;
    gear: GearItem[];
    items: string[];
    deploy?: { status: string; message?: string };
}

interface DeployStatus {
    type: 'deploy-status';
    status: string;  // idle | deploying | running | stopped | error
    message?: string;
    pid?: number;
    server?: string;
}

interface GearCatalog {
    type: 'gear-catalog';
    budget: number;
    gear: Record<string, { slot: string; cost: number; effects: Record<string, any> }>;
}

// Stat display config
const STAT_BARS = [
    { key: 'max-hp',           label: 'HP',     max: 750, color: 0x4ecdc4, defaultVal: 500 },
    { key: 'shoot-damage',     label: 'DMG',    max: 60,  color: 0xff6b6b, defaultVal: 30 },
    { key: 'shoot-range',      label: 'RANGE',  max: 12,  color: 0xffd93d, defaultVal: 5 },
    { key: 'speed',            label: 'SPEED',  max: 2,   color: 0x6bcb77, defaultVal: 1 },
    { key: 'visibility-radius', label: 'VISION', max: 8,   color: 0xa78bfa, defaultVal: 5 },
    { key: 'start-ammo',       label: 'AMMO',   max: 15,  color: 0xf97316, defaultVal: 5 },
    { key: 'start-grenades',   label: 'NADES',  max: 5,   color: 0xef4444, defaultVal: 2 },
    { key: 'shield-hp',        label: 'SHIELD', max: 50,  color: 0x38bdf8, defaultVal: 0 },
];

// Gear slot labels and icons
const SLOT_LABELS: Record<string, string> = {
    weapon: 'WEAPON',
    armor: 'ARMOR',
    movement: 'MOVE',
    utility: 'UTIL',
};

const GEAR_DISPLAY_NAMES: Record<string, string> = {
    'standard-blaster': 'Standard Blaster',
    'shotgun': 'Shotgun',
    'sniper-rifle': 'Sniper Rifle',
    'plasma-cannon': 'Plasma Cannon',
    'light-vest': 'Light Vest',
    'heavy-armor': 'Heavy Armor',
    'energy-shield': 'Energy Shield',
    'speed-boost': 'Speed Boost',
    'teleporter': 'Teleporter',
    'radar': 'Radar',
    'extra-ammo': 'Extra Ammo',
    'grenades-plus': 'Grenades+',
    'trap-mine': 'Trap Mine',
    'decoy': 'Decoy',
};

export class LobbyScene extends Phaser.Scene {

    constructor() {
        super({ key: 'LobbyScene' });
    }

    ws: WebSocket | null = null;
    connected: boolean = false;

    // Character
    characterSprite: Phaser.GameObjects.Sprite | null = null;
    characterImage: Phaser.GameObjects.Image | null = null;  // for custom avatars
    currentAvatar: string = '';
    currentAvatarImage: string = '';  // tracks data URL to detect changes
    bobTween: Phaser.Tweens.Tween | null = null;

    // UI elements
    nameText!: Phaser.GameObjects.Text;
    connectionText!: Phaser.GameObjects.Text;
    budgetText!: Phaser.GameObjects.Text;
    budgetBarBg!: Phaser.GameObjects.Graphics;
    budgetBarFill!: Phaser.GameObjects.Graphics;
    errorText!: Phaser.GameObjects.Text;

    // Deploy
    deployButton!: Phaser.GameObjects.Text;
    deployButtonBg!: Phaser.GameObjects.Graphics;
    deployStatus!: Phaser.GameObjects.Text;
    currentDeployState: string = 'idle';
    loadoutValid: boolean = false;

    // Stat bars
    statBarGraphics!: Phaser.GameObjects.Graphics;
    statLabels: Phaser.GameObjects.Text[] = [];
    statValues: Phaser.GameObjects.Text[] = [];

    // Gear panel
    gearTexts: Phaser.GameObjects.Text[] = [];

    // Animated values for smooth transitions
    animatedStats: Record<string, number> = {};

    // Layout constants
    readonly CX = 672;   // center X (1344/2)
    readonly CY = 500;   // character Y position
    readonly STATS_X = 120;
    readonly GEAR_X = 1000;

    create(): void {
        const { width, height } = this.scale;

        // Dark background with subtle gradient
        const bg = this.add.graphics();
        bg.fillStyle(0x0a0a1a, 1);
        bg.fillRect(0, 0, width, height);

        // Floor grid lines for depth
        const floor = this.add.graphics();
        floor.lineStyle(1, 0x1a1a3a, 0.3);
        for (let y = 700; y < height; y += 30) {
            floor.lineBetween(0, y, width, y);
        }
        for (let x = 0; x < width; x += 60) {
            floor.lineBetween(x, 700, x + (height - 700) * 0.3, height);
        }

        // Spotlight effect behind character
        const spotlight = this.add.graphics();
        spotlight.fillStyle(0x2a2a5a, 0.3);
        spotlight.fillEllipse(this.CX, this.CY + 100, 300, 60);

        // Title
        this.add.text(this.CX, 40, 'GEAR ROOM', {
            fontSize: '42px', color: '#4ecdc4', fontFamily: 'monospace',
            fontStyle: 'bold', stroke: '#000', strokeThickness: 4,
        }).setOrigin(0.5, 0).setDepth(10);

        // Bot name
        this.nameText = this.add.text(this.CX, 95, 'Connecting...', {
            fontSize: '28px', color: '#fff', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 3,
        }).setOrigin(0.5, 0).setDepth(10);

        // Connection status
        this.connectionText = this.add.text(width - 20, 20, 'DISCONNECTED', {
            fontSize: '14px', color: '#ff6b6b', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2,
        }).setOrigin(1, 0).setDepth(10);

        // ---- Stats panel (left side) ----
        this.add.text(this.STATS_X, 180, 'STATS', {
            fontSize: '24px', color: '#4ecdc4', fontFamily: 'monospace',
            fontStyle: 'bold', stroke: '#000', strokeThickness: 3,
        }).setOrigin(0.5, 0).setDepth(10);

        this.statBarGraphics = this.add.graphics().setDepth(5);

        for (let i = 0; i < STAT_BARS.length; i++) {
            const y = 230 + i * 52;
            const label = this.add.text(30, y, STAT_BARS[i].label, {
                fontSize: '16px', color: '#888', fontFamily: 'monospace',
                stroke: '#000', strokeThickness: 2,
            }).setDepth(10);
            this.statLabels.push(label);

            const val = this.add.text(210, y, '', {
                fontSize: '16px', color: '#fff', fontFamily: 'monospace',
                stroke: '#000', strokeThickness: 2,
            }).setDepth(10);
            this.statValues.push(val);

            this.animatedStats[STAT_BARS[i].key] = STAT_BARS[i].defaultVal;
        }

        // ---- Gear panel (right side) ----
        this.add.text(this.GEAR_X + 100, 180, 'EQUIPPED GEAR', {
            fontSize: '24px', color: '#4ecdc4', fontFamily: 'monospace',
            fontStyle: 'bold', stroke: '#000', strokeThickness: 3,
        }).setOrigin(0.5, 0).setDepth(10);

        // ---- Budget bar (bottom) ----
        this.budgetBarBg = this.add.graphics().setDepth(5);
        this.budgetBarFill = this.add.graphics().setDepth(6);
        this.budgetText = this.add.text(this.CX, height - 60, '0 / 100 pts', {
            fontSize: '22px', color: '#fff', fontFamily: 'monospace',
            fontStyle: 'bold', stroke: '#000', strokeThickness: 3,
        }).setOrigin(0.5, 0).setDepth(10);

        this.add.text(this.CX, height - 90, 'BUDGET', {
            fontSize: '16px', color: '#888', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2,
        }).setOrigin(0.5, 0).setDepth(10);

        // Error text
        this.errorText = this.add.text(this.CX, height - 130, '', {
            fontSize: '16px', color: '#ff6b6b', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2, align: 'center',
            wordWrap: { width: 600 },
        }).setOrigin(0.5, 0.5).setDepth(10);

        // Instruction text
        this.add.text(this.CX, height - 25, 'Edit loadout.py to change gear — auto-refreshes!', {
            fontSize: '14px', color: '#555', fontFamily: 'monospace',
        }).setOrigin(0.5, 0.5).setDepth(10);

        // ---- Deploy button (right side, below gear) ----
        const deployX = this.GEAR_X + 100;
        const deployY = 720;

        this.deployButtonBg = this.add.graphics().setDepth(14);
        this.drawDeployButton(deployX, deployY, 0x2a6e4e, false);

        this.deployButton = this.add.text(deployX, deployY, 'DEPLOY', {
            fontSize: '28px', color: '#fff', fontFamily: 'monospace',
            fontStyle: 'bold', stroke: '#000', strokeThickness: 3,
        }).setOrigin(0.5, 0.5).setDepth(15).setInteractive({ useHandCursor: true });

        this.deployButton.on('pointerover', () => {
            if (this.currentDeployState === 'running') return;
            this.drawDeployButton(deployX, deployY, 0x3a9e6e, true);
        });
        this.deployButton.on('pointerout', () => {
            if (this.currentDeployState === 'running') return;
            const color = this.loadoutValid ? 0x2a6e4e : 0x444444;
            this.drawDeployButton(deployX, deployY, color, false);
        });
        this.deployButton.on('pointerdown', () => {
            if (this.currentDeployState === 'running') {
                // Stop the bot
                this.ws?.send(JSON.stringify({ action: 'stop' }));
            } else if (this.loadoutValid) {
                this.ws?.send(JSON.stringify({ action: 'deploy' }));
            }
        });

        this.deployStatus = this.add.text(deployX, deployY + 35, '', {
            fontSize: '14px', color: '#888', fontFamily: 'monospace',
            stroke: '#000', strokeThickness: 2,
        }).setOrigin(0.5, 0).setDepth(15);

        // Draw initial budget bar
        this.drawBudgetBar(0, 100);

        // Connect to lobby WebSocket
        this.connectWS();
    }

    connectWS(): void {
        const wsUrl = 'ws://localhost:9876';
        this.connectionText.setText('CONNECTING...');
        this.connectionText.setColor('#ffd93d');

        try {
            this.ws = new WebSocket(wsUrl);
        } catch (e) {
            this.connectionText.setText('CONNECTION FAILED');
            this.connectionText.setColor('#ff6b6b');
            this.time.delayedCall(3000, () => this.connectWS());
            return;
        }

        this.ws.onopen = () => {
            this.connected = true;
            this.connectionText.setText('CONNECTED');
            this.connectionText.setColor('#6bcb77');
        };

        this.ws.onclose = () => {
            this.connected = false;
            this.connectionText.setText('DISCONNECTED');
            this.connectionText.setColor('#ff6b6b');
            this.time.delayedCall(2000, () => this.connectWS());
        };

        this.ws.onerror = () => {
            this.ws?.close();
        };

        this.ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'loadout-update') {
                    this.handleLoadoutUpdate(data as LoadoutUpdate);
                } else if (data.type === 'deploy-status') {
                    this.handleDeployStatus(data as DeployStatus);
                }
                // gear-catalog could be used for a full item browser later
            } catch (e) {
                console.error('WS parse error:', e);
            }
        };
    }

    handleLoadoutUpdate(data: LoadoutUpdate): void {
        // Name
        this.nameText.setText(data.name);

        // Errors
        if (!data.valid) {
            this.errorText.setText(data.errors.join('\n'));
            this.errorText.setColor('#ff6b6b');
            // Flash the error
            this.tweens.add({
                targets: this.errorText,
                alpha: { from: 0, to: 1 },
                duration: 200,
            });
        } else {
            this.errorText.setText('');
        }

        // Character avatar
        this.updateCharacter(data.avatar, data.avatar_image);

        // Stats
        this.updateStats(data.stats);

        // Gear list
        this.updateGearPanel(data.gear);

        // Budget
        this.drawBudgetBar(data.cost, data.budget);
        this.budgetText.setText(`${data.cost} / ${data.budget} pts`);
        if (data.cost > data.budget) {
            this.budgetText.setColor('#ff6b6b');
        } else if (data.cost > data.budget * 0.8) {
            this.budgetText.setColor('#ffd93d');
        } else {
            this.budgetText.setColor('#fff');
        }

        // Track validity for deploy button
        this.loadoutValid = data.valid;
        if (this.currentDeployState === 'idle' || this.currentDeployState === 'stopped') {
            const deployX = this.GEAR_X + 100;
            const deployY = 720;
            this.drawDeployButton(deployX, deployY, data.valid ? 0x2a6e4e : 0x444444, false);
        }

        // Update deploy status if included
        if (data.deploy) {
            this.handleDeployStatus({ type: 'deploy-status', ...data.deploy } as DeployStatus);
        }

        // Sparkle effect on valid update
        if (data.valid) {
            this.spawnSparkles();
        }
    }

    updateCharacter(avatar: string, avatarImage?: string): void {
        // Decide if we're using a custom image or a built-in Rick spritesheet
        const useCustom = !!avatarImage;
        const spriteKey = RICK_VARIANTS.find(r => r === avatar) || 'TinyRick';
        const cacheKey = useCustom ? avatarImage! : spriteKey;

        // Skip if nothing changed
        if (cacheKey === this.currentAvatarImage) return;

        // Fade out old character (sprite or image)
        const oldTarget = this.characterSprite || this.characterImage;
        if (oldTarget) {
            const old = oldTarget;
            this.tweens.add({
                targets: old,
                alpha: 0,
                scaleX: 0.5,
                scaleY: 0.5,
                duration: 200,
                onComplete: () => old.destroy(),
            });
            if (this.bobTween) {
                this.bobTween.stop();
                this.bobTween = null;
            }
            this.characterSprite = null;
            this.characterImage = null;
        }

        if (useCustom) {
            // Load custom avatar from data URL
            const texKey = 'custom-avatar-' + Date.now();
            const img = new Image();
            img.onload = () => {
                if (this.textures.exists(texKey)) return;
                this.textures.addImage(texKey, img);
                this.characterImage = this.add.image(this.CX, this.CY, texKey);
                // Scale to fit ~250px tall, preserving aspect ratio
                const targetH = 250;
                const scale = targetH / img.height;
                this.characterImage.setScale(scale);
                this.characterImage.setDepth(20);
                this.characterImage.setAlpha(0);

                this.tweens.add({
                    targets: this.characterImage,
                    alpha: 1,
                    duration: 300,
                    ease: 'Back.easeOut',
                });

                this.bobTween = this.tweens.add({
                    targets: this.characterImage,
                    y: this.CY - 8,
                    duration: 1500,
                    yoyo: true,
                    repeat: -1,
                    ease: 'Sine.easeInOut',
                });
            };
            img.src = avatarImage!;
        } else {
            // Built-in Rick spritesheet
            const animPrefix = spriteKey.toLowerCase().replace(/[^a-z0-9]/g, '');
            this.characterSprite = this.add.sprite(this.CX, this.CY, spriteKey, `${spriteKey}_down_1`);
            this.characterSprite.setScale(4);
            this.characterSprite.setDepth(20);
            this.characterSprite.setAlpha(0);

            this.tweens.add({
                targets: this.characterSprite,
                alpha: 1,
                scaleX: 4,
                scaleY: 4,
                duration: 300,
                ease: 'Back.easeOut',
            });

            this.characterSprite.play(`${animPrefix}-walk-down`);

            this.bobTween = this.tweens.add({
                targets: this.characterSprite,
                y: this.CY - 8,
                duration: 1500,
                yoyo: true,
                repeat: -1,
                ease: 'Sine.easeInOut',
            });
        }

        this.currentAvatar = spriteKey;
        this.currentAvatarImage = cacheKey;
    }

    updateStats(stats: Record<string, number | boolean>): void {
        this.statBarGraphics.clear();

        for (let i = 0; i < STAT_BARS.length; i++) {
            const bar = STAT_BARS[i];
            const y = 230 + i * 52;
            const rawVal = stats[bar.key];
            const value = typeof rawVal === 'number' ? rawVal : (rawVal ? 1 : 0);

            // Animate toward target value
            const current = this.animatedStats[bar.key] ?? bar.defaultVal;
            const target = value;
            this.animatedStats[bar.key] = current + (target - current) * 0.3;
            const displayVal = Math.round(this.animatedStats[bar.key]);

            const barWidth = 170;
            const barHeight = 20;
            const barX = 30;
            const barY = y + 22;
            const fillPct = Math.min(1, displayVal / bar.max);
            const defaultPct = Math.min(1, bar.defaultVal / bar.max);

            // Background
            this.statBarGraphics.fillStyle(0x1a1a2e, 1);
            this.statBarGraphics.fillRect(barX, barY, barWidth, barHeight);

            // Default marker line
            this.statBarGraphics.lineStyle(1, 0x444, 0.5);
            this.statBarGraphics.lineBetween(
                barX + barWidth * defaultPct, barY,
                barX + barWidth * defaultPct, barY + barHeight
            );

            // Fill bar
            const isAboveDefault = displayVal > bar.defaultVal;
            const fillColor = isAboveDefault ? bar.color : 0x666666;
            this.statBarGraphics.fillStyle(fillColor, 1);
            this.statBarGraphics.fillRect(barX, barY, barWidth * fillPct, barHeight);

            // Border
            this.statBarGraphics.lineStyle(1, 0x333, 1);
            this.statBarGraphics.strokeRect(barX, barY, barWidth, barHeight);

            // Value text
            this.statValues[i].setText(String(displayVal));
            this.statValues[i].setColor(isAboveDefault ? '#4ecdc4' : '#888');
        }
    }

    updateGearPanel(gear: GearItem[]): void {
        // Clear old gear texts
        for (const t of this.gearTexts) {
            t.destroy();
        }
        this.gearTexts = [];

        if (gear.length === 0) {
            const t = this.add.text(this.GEAR_X, 230, 'No gear equipped', {
                fontSize: '16px', color: '#555', fontFamily: 'monospace',
                fontStyle: 'italic', stroke: '#000', strokeThickness: 2,
            }).setDepth(10);
            this.gearTexts.push(t);
            return;
        }

        // Group by slot
        const slots: Record<string, GearItem[]> = {};
        for (const item of gear) {
            if (!slots[item.slot]) slots[item.slot] = [];
            slots[item.slot].push(item);
        }

        let yOffset = 230;
        const slotOrder = ['weapon', 'armor', 'movement', 'utility'];

        for (const slot of slotOrder) {
            const items = slots[slot];
            if (!items) continue;

            // Slot header
            const header = this.add.text(this.GEAR_X, yOffset, SLOT_LABELS[slot] || slot.toUpperCase(), {
                fontSize: '14px', color: '#4ecdc4', fontFamily: 'monospace',
                fontStyle: 'bold', stroke: '#000', strokeThickness: 2,
            }).setDepth(10);
            this.gearTexts.push(header);
            yOffset += 26;

            for (const item of items) {
                const displayName = GEAR_DISPLAY_NAMES[item.key] || item.key;

                // Item name + cost
                const itemText = this.add.text(this.GEAR_X + 15, yOffset, `${displayName}`, {
                    fontSize: '18px', color: '#fff', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 2,
                }).setDepth(10);
                this.gearTexts.push(itemText);

                const costText = this.add.text(this.GEAR_X + 280, yOffset, `${item.cost} pts`, {
                    fontSize: '14px', color: '#888', fontFamily: 'monospace',
                    stroke: '#000', strokeThickness: 2,
                }).setDepth(10);
                this.gearTexts.push(costText);

                yOffset += 28;

                // Effect summary
                const effects = Object.entries(item.effects)
                    .map(([k, v]) => `${k}: ${v}`)
                    .join(', ');
                if (effects) {
                    const effectText = this.add.text(this.GEAR_X + 25, yOffset, effects, {
                        fontSize: '12px', color: '#666', fontFamily: 'monospace',
                        stroke: '#000', strokeThickness: 1,
                    }).setDepth(10);
                    this.gearTexts.push(effectText);
                    yOffset += 22;
                }
            }
            yOffset += 10;
        }
    }

    drawDeployButton(cx: number, cy: number, color: number, hover: boolean): void {
        this.deployButtonBg.clear();
        const w = 200, h = 50, r = 12;
        this.deployButtonBg.fillStyle(color, 1);
        this.deployButtonBg.fillRoundedRect(cx - w / 2, cy - h / 2, w, h, r);
        if (hover) {
            this.deployButtonBg.lineStyle(2, 0x4ecdc4, 1);
            this.deployButtonBg.strokeRoundedRect(cx - w / 2, cy - h / 2, w, h, r);
        }
    }

    handleDeployStatus(data: DeployStatus): void {
        this.currentDeployState = data.status;
        const deployX = this.GEAR_X + 100;
        const deployY = 720;

        switch (data.status) {
            case 'idle':
                this.deployButton.setText('DEPLOY');
                this.drawDeployButton(deployX, deployY, this.loadoutValid ? 0x2a6e4e : 0x444444, false);
                this.deployStatus.setText('');
                break;
            case 'deploying':
                this.deployButton.setText('...');
                this.drawDeployButton(deployX, deployY, 0x886600, false);
                this.deployStatus.setText(data.message || 'Starting...');
                this.deployStatus.setColor('#ffd93d');
                break;
            case 'running':
                this.deployButton.setText('STOP');
                this.drawDeployButton(deployX, deployY, 0x993333, false);
                this.deployStatus.setText(data.message || 'Bot is live!');
                this.deployStatus.setColor('#6bcb77');
                break;
            case 'stopped':
                this.deployButton.setText('DEPLOY');
                this.drawDeployButton(deployX, deployY, this.loadoutValid ? 0x2a6e4e : 0x444444, false);
                this.deployStatus.setText(data.message || 'Bot stopped');
                this.deployStatus.setColor('#888');
                break;
            case 'error':
                this.deployButton.setText('DEPLOY');
                this.drawDeployButton(deployX, deployY, 0x993333, false);
                this.deployStatus.setText(data.message || 'Error');
                this.deployStatus.setColor('#ff6b6b');
                break;
        }
    }

    drawBudgetBar(cost: number, budget: number): void {
        const { height } = this.scale;
        const barWidth = 500;
        const barHeight = 24;
        const barX = this.CX - barWidth / 2;
        const barY = height - 55;

        this.budgetBarBg.clear();
        this.budgetBarBg.fillStyle(0x1a1a2e, 1);
        this.budgetBarBg.fillRect(barX, barY, barWidth, barHeight);
        this.budgetBarBg.lineStyle(1, 0x333, 1);
        this.budgetBarBg.strokeRect(barX, barY, barWidth, barHeight);

        this.budgetBarFill.clear();
        const pct = Math.min(1, cost / budget);
        const color = cost > budget ? 0xff6b6b : (pct > 0.8 ? 0xffd93d : 0x4ecdc4);
        this.budgetBarFill.fillStyle(color, 1);
        this.budgetBarFill.fillRect(barX + 2, barY + 2, (barWidth - 4) * pct, barHeight - 4);
    }

    spawnSparkles(): void {
        if (!this.characterSprite) return;
        for (let i = 0; i < 8; i++) {
            const angle = (Math.PI * 2 / 8) * i;
            const dist = 40 + Math.random() * 30;
            const x = this.CX + Math.cos(angle) * dist;
            const y = this.CY + Math.sin(angle) * dist;

            const spark = this.add.circle(x, y, 3, 0x4ecdc4, 1).setDepth(30);
            this.tweens.add({
                targets: spark,
                x: x + Math.cos(angle) * 40,
                y: y + Math.sin(angle) * 40 - 20,
                alpha: 0,
                scaleX: 0,
                scaleY: 0,
                duration: 500 + Math.random() * 300,
                ease: 'Quad.easeOut',
                onComplete: () => spark.destroy(),
            });
        }
    }

    update(): void {
        // Continuously animate stat bars toward target values
        if (this.statBarGraphics) {
            // The stat bars self-animate via the animatedStats lerp in updateStats
            // We just need to trigger a redraw if values are still converging
            let needsRedraw = false;
            for (const bar of STAT_BARS) {
                const current = this.animatedStats[bar.key] ?? bar.defaultVal;
                const rounded = Math.round(current);
                if (Math.abs(current - rounded) > 0.01) {
                    needsRedraw = true;
                    break;
                }
            }
            // Stats will be redrawn on next loadout update
        }
    }
}
