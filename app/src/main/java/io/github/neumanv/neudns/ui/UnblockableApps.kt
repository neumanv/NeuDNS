package io.github.neumanv.neudns.ui

/**
 * Apps que sirven los anuncios desde los mismos servidores que su contenido (vídeos, publicaciones,
 * canciones): un filtro DNS no puede quitarlos sin dejarlas sin funcionar, así que no se ofrecen
 * y la pantalla explica el motivo.
 */
internal val UNBLOCKABLE_APPS = setOf(
    // YouTube
    "com.google.android.youtube",
    "com.google.android.apps.youtube.music",
    "com.google.android.apps.youtube.kids",
    "com.google.android.youtube.tv",
    // Meta
    "com.facebook.katana",
    "com.facebook.lite",
    "com.facebook.orca",
    "com.instagram.android",
    "com.instagram.lite",
    "com.instagram.barcelona", // Threads
    // TikTok
    "com.zhiliaoapp.musically",
    "com.zhiliaoapp.musically.go",
    "com.ss.android.ugc.trill",
    // Otras redes y plataformas con anuncios propios
    "com.twitter.android",
    "com.snapchat.android",
    "com.reddit.frontpage",
    "com.pinterest",
    "com.linkedin.android",
    "tv.twitch.android.app",
    "com.spotify.music",
)
