package com.reau.app.dash

/**
 * How the encoder spends the bitrate it is given: [CBR] keeps every second at the bitrate, so a map moving fast at
 * speed cannot burst past it; [VBR] lets a busy second spend more and a still one less (what the codec does by itself
 * when no mode is set, peaks of 1374 kbps against 1024 set). The dash is known to accept [VBR]; [CBR] is still to be
 * checked on the bike.
 */
enum class BitrateMode { CBR, VBR }
