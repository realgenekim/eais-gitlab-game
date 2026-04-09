// CONFIGURABLE GAME OPTIONS
// changing these values will affect gameplay

export const GameOptions : any = {

    gameSize : {
        width               : 1344,     // 21 tiles × 64px
        height              : 1216      // 19 tiles × 64px
    },
    gameBackgroundColor     : 0x1a1a2e, // lighter floor so logo shows better

    playerSpeed             : 100,      // player speed, in pixels per second
    enemySpeed              : 50,       // enemy speed, in pixels per second
    bulletSpeed             : 200,      // bullet speed, in pixels per second
    bulletRate              : 1000,     // bullet rate, in milliseconds per bullet
    enemyRate               : 800,      // enemy rate, in milliseconds per enemy
    magnetRadius            : 100,      // radius of the circle within which the coins are being attracted

    // Server connection — auto-detect from page URL, fallback to localhost
    serverUrl               : (typeof window !== 'undefined' && window.location.hostname !== 'localhost')
                                ? window.location.origin
                                : 'http://localhost:33333',
    serverWsUrl             : (typeof window !== 'undefined' && window.location.hostname !== 'localhost')
                                ? (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + window.location.host + '/spectate-ws'
                                : 'ws://localhost:33333/spectate-ws',
    tickMs                  : 250       // server tick interval for interpolation
}
