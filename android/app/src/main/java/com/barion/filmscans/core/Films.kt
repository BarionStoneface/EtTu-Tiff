package com.barion.filmscans.core

data class FilmStock(val name: String, val iso: Int)

/** The film stock dropdown. Anything else can be typed in. */
val FILM_STOCKS = listOf(
    FilmStock("Kodak Portra 160", 160), FilmStock("Kodak Portra 400", 400), FilmStock("Kodak Portra 800", 800),
    FilmStock("Kodak Ektar 100", 100), FilmStock("Kodak Gold 200", 200), FilmStock("Kodak UltraMax 400", 400),
    FilmStock("Kodak ColorPlus 200", 200), FilmStock("Kodak ProImage 100", 100), FilmStock("Kodak Ektachrome E100", 100),
    FilmStock("Kodak Tri-X 400", 400), FilmStock("Kodak T-Max 100", 100), FilmStock("Kodak T-Max 400", 400),
    FilmStock("Kodak T-Max P3200", 3200), FilmStock("Kodak Vision3 50D", 50), FilmStock("Kodak Vision3 250D", 250),
    FilmStock("Kodak Vision3 200T", 200), FilmStock("Kodak Vision3 500T", 500),
    FilmStock("Fujifilm Fujicolor 200", 200), FilmStock("Fujifilm Superia X-TRA 400", 400),
    FilmStock("Fujifilm Fujicolor C200", 200), FilmStock("Fujifilm Velvia 50", 50), FilmStock("Fujifilm Velvia 100", 100),
    FilmStock("Fujifilm Provia 100F", 100), FilmStock("Fujifilm Acros 100 II", 100),
    FilmStock("Ilford HP5 Plus 400", 400), FilmStock("Ilford FP4 Plus 125", 125), FilmStock("Ilford Delta 100", 100),
    FilmStock("Ilford Delta 400", 400), FilmStock("Ilford Delta 3200", 3200), FilmStock("Ilford Pan F Plus 50", 50),
    FilmStock("Ilford XP2 Super 400", 400), FilmStock("Ilford SFX 200", 200), FilmStock("Ilford Ortho Plus 80", 80),
    FilmStock("Kentmere Pan 100", 100), FilmStock("Kentmere Pan 400", 400),
    FilmStock("CineStill 800T", 800), FilmStock("CineStill 400D", 400), FilmStock("CineStill 50D", 50),
    FilmStock("Lomography Color Negative 100", 100), FilmStock("Lomography Color Negative 400", 400),
    FilmStock("Lomography Color Negative 800", 800), FilmStock("LomoChrome Purple", 400),
    FilmStock("LomoChrome Metropolis", 400), FilmStock("Lomography Lady Grey 400", 400),
    FilmStock("Lomography Earl Grey 100", 100), FilmStock("Lomography Berlin Kino 400", 400),
    FilmStock("Harman Phoenix 200", 200),
    FilmStock("Fomapan 100", 100), FilmStock("Fomapan 200", 200), FilmStock("Fomapan 400", 400),
    FilmStock("Rollei RPX 25", 25), FilmStock("Rollei RPX 100", 100), FilmStock("Rollei RPX 400", 400),
    FilmStock("Rollei Retro 80S", 80), FilmStock("Rollei Infrared 400", 400),
    FilmStock("Adox CHS 100 II", 100), FilmStock("Adox Silvermax 100", 100),
    FilmStock("Agfa APX 100", 100), FilmStock("Agfa APX 400", 400),
)

/** Tags for the "experimental" chips. */
val PROCESS_TAGS = listOf("Experimental", "Expired", "Redscale", "Cross-processed", "Film soup",
    "Double exposure", "Pre-exposed", "Home developed", "Stand developed")

fun isoFor(film: String): Int? =
    FILM_STOCKS.firstOrNull { it.name.equals(film.trim(), ignoreCase = true) }?.iso
        ?: Regex("""\b(\d{2,4})[DT]?\b""").findAll(film).map { it.groupValues[1].toInt() }
            .firstOrNull { it in listOf(25, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 800, 1600, 3200) }
