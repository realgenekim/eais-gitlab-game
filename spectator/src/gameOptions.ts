// CONFIGURABLE GAME OPTIONS
// changing these values will affect gameplay

export const GameOptions : any = {

    gameSize : {
        width               : 1280,     // 20 tiles × 64px
        height              : 1280      // 20 tiles × 64px
    },
    gameBackgroundColor     : 0x222222, // game background color

    playerSpeed             : 100,      // player speed, in pixels per second
    enemySpeed              : 50,       // enemy speed, in pixels per second
    bulletSpeed             : 200,      // bullet speed, in pixels per second
    bulletRate              : 1000,     // bullet rate, in milliseconds per bullet
    enemyRate               : 800,      // enemy rate, in milliseconds per enemy
    magnetRadius            : 100,      // radius of the circle within which the coins are being attracted

    // Server connection
    serverUrl               : 'ws://localhost:8080/spectate-ws',
    tickMs                  : 500       // server tick interval for interpolation
}
