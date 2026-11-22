package work.localize.sdk

/**
 * CLDR cardinal plural categories for whole numbers.
 *
 * The rules are written out here instead of asking the platform, so that every
 * Android version (and a plain JVM in unit tests) picks the same form. They
 * follow CLDR 49 and are checked in the unit tests against vectors generated
 * from the CLDR data for all of its languages; see
 * `tools/generate-plural-vectors.mjs`.
 *
 * Only whole numbers are covered. Decimal counts have their own rules in many
 * languages and are out of scope for this version.
 */
internal object PluralRules {
    const val ZERO: String = "zero"
    const val ONE: String = "one"
    const val TWO: String = "two"
    const val FEW: String = "few"
    const val MANY: String = "many"
    const val OTHER: String = "other"

    private fun interface Rule {
        fun select(n: Long): String
    }

    /** Used for a language the table does not know: singular for exactly one. */
    private val unknownLanguage = Rule { n -> if (n == 1L) ONE else OTHER }

    private val rules: Map<String, Rule> = buildMap {
        fun family(languages: String, rule: Rule) {
            for (language in languages.split(' ')) put(language, rule)
        }

        // No plural forms.
        family(
            "bm bo dz hnj id ig ii ja jbo jv jw kde kea km ko lkt lo ms my nqo osa sah ses sg su th to " +
                "tpi und wo yo yue zh",
        ) { OTHER }

        // "one" for exactly 1.
        family(
            "af an asa ast az bal bem bez bg brx ce cgg chr ckb da de dv ee el en eo et eu fi fo fur fy " +
                "gsw ha haw hu ia ie io jgo jmc ka kaj kcg kk kkj kl ks ksb ku ky lb lg lij mas mgo ml mn " +
                "mr nah nb nd ne nl nn nnh no nr ny nyn om or os pap ps rm rof rwk saq sc sd sdh seh sn " +
                "so sq ss ssy st sv sw syr ta te teo tig tk tn tr ts ug ur uz ve vo vun wae xh xog yi",
        ) { n -> if (n == 1L) ONE else OTHER }

        // "one" for 0 and 1.
        family(
            "ak am as bho bn csw doi fa ff gu guw hi hy kab kn kok ln mg nso pa pcm si tg ti vi wa zu",
        ) { n -> if (n <= 1L) ONE else OTHER }

        // "one" for 1, "many" for whole millions.
        family("ca es gl it lld pt-pt scn vec") { n ->
            when {
                n == 1L -> ONE
                n != 0L && n % 1_000_000L == 0L -> MANY
                else -> OTHER
            }
        }

        // "one" for 0 and 1, "many" for whole millions.
        family("fr pt") { n ->
            when {
                n <= 1L -> ONE
                n % 1_000_000L == 0L -> MANY
                else -> OTHER
            }
        }

        family("be ru uk") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L -> ONE
                mod10 in 2..4 && mod100 !in 12..14 -> FEW
                else -> MANY
            }
        }

        family("pl") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                n == 1L -> ONE
                mod10 in 2..4 && mod100 !in 12..14 -> FEW
                else -> MANY
            }
        }

        family("bs hr sh sr") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L -> ONE
                mod10 in 2..4 && mod100 !in 12..14 -> FEW
                else -> OTHER
            }
        }

        family("cs sk") { n ->
            when (n) {
                1L -> ONE
                in 2..4 -> FEW
                else -> OTHER
            }
        }

        family("dsb hsb sl") { n ->
            when (n % 100) {
                1L -> ONE
                2L -> TWO
                3L, 4L -> FEW
                else -> OTHER
            }
        }

        family("lt") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 !in 11..19 -> ONE
                mod10 in 2..9 && mod100 !in 11..19 -> FEW
                else -> OTHER
            }
        }

        family("sgs") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L -> ONE
                n == 2L -> TWO
                mod10 in 2..9 && mod100 !in 11..19 -> FEW
                else -> OTHER
            }
        }

        family("lv prg") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 0L || mod100 in 11..19 -> ZERO
                mod10 == 1L && mod100 != 11L -> ONE
                else -> OTHER
            }
        }

        family("mo ro") { n ->
            when {
                n == 1L -> ONE
                n == 0L || n % 100 in 1..19 -> FEW
                else -> OTHER
            }
        }

        family("ar ars") { n ->
            val mod100 = n % 100
            when {
                n == 0L -> ZERO
                n == 1L -> ONE
                n == 2L -> TWO
                mod100 in 3..10 -> FEW
                mod100 in 11..99 -> MANY
                else -> OTHER
            }
        }

        // "one" for 1, "two" for 2.
        family("he iu naq sat se sma smi smj smn sms") { n ->
            when (n) {
                1L -> ONE
                2L -> TWO
                else -> OTHER
            }
        }

        // "zero" for 0, "one" for 1.
        family("blo cv ksh lag") { n ->
            when (n) {
                0L -> ZERO
                1L -> ONE
                else -> OTHER
            }
        }

        family("is mk") { n -> if (n % 10 == 1L && n % 100 != 11L) ONE else OTHER }

        family("ceb fil tl") { n ->
            when (n % 10) {
                4L, 6L, 9L -> OTHER
                else -> ONE
            }
        }

        family("mt") { n ->
            val mod100 = n % 100
            when {
                n == 1L -> ONE
                n == 2L -> TWO
                n == 0L || mod100 in 3..10 -> FEW
                mod100 in 11..19 -> MANY
                else -> OTHER
            }
        }

        family("ga") { n ->
            when (n) {
                1L -> ONE
                2L -> TWO
                in 3..6 -> FEW
                in 7..10 -> MANY
                else -> OTHER
            }
        }

        family("gd") { n ->
            when (n) {
                1L, 11L -> ONE
                2L, 12L -> TWO
                in 3..10, in 13..19 -> FEW
                else -> OTHER
            }
        }

        family("cy") { n ->
            when (n) {
                0L -> ZERO
                1L -> ONE
                2L -> TWO
                3L -> FEW
                6L -> MANY
                else -> OTHER
            }
        }

        family("br") { n ->
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L && mod100 != 71L && mod100 != 91L -> ONE
                mod10 == 2L && mod100 != 12L && mod100 != 72L && mod100 != 92L -> TWO
                (mod10 == 3L || mod10 == 4L || mod10 == 9L) &&
                    mod100 !in 10..19 && mod100 !in 70..79 && mod100 !in 90..99 -> FEW
                n != 0L && n % 1_000_000L == 0L -> MANY
                else -> OTHER
            }
        }

        family("gv") { n ->
            val mod10 = n % 10
            when {
                mod10 == 1L -> ONE
                mod10 == 2L -> TWO
                n % 20 == 0L -> FEW
                else -> OTHER
            }
        }

        family("kw") { n ->
            val mod100 = n % 100
            val mod100000 = n % 100_000
            when {
                n == 0L -> ZERO
                n == 1L -> ONE
                mod100 == 2L || mod100 == 22L || mod100 == 42L || mod100 == 62L || mod100 == 82L -> TWO
                n % 1000 == 0L && (
                    mod100000 in 1000..20000 || mod100000 == 40000L || mod100000 == 60000L ||
                        mod100000 == 80000L
                    ) -> TWO
                n % 1_000_000L == 100_000L -> TWO
                mod100 == 3L || mod100 == 23L || mod100 == 43L || mod100 == 63L || mod100 == 83L -> FEW
                mod100 == 1L || mod100 == 21L || mod100 == 41L || mod100 == 61L || mod100 == 81L -> MANY
                else -> OTHER
            }
        }

        family("shi") { n ->
            when {
                n <= 1L -> ONE
                n in 2..10 -> FEW
                else -> OTHER
            }
        }

        family("tzm") { n -> if (n <= 1L || n in 11..99) ONE else OTHER }
    }

    /**
     * The category of [count] in the language of [locale] (a BCP 47 tag such as
     * `ru` or `pt-PT`). A negative count is treated as its absolute value.
     */
    fun category(locale: String, count: Long): String {
        val n = when {
            count >= 0 -> count
            count == Long.MIN_VALUE -> Long.MAX_VALUE
            else -> -count
        }
        return ruleFor(locale).select(n)
    }

    /** Whether the table has rules for the language of [locale]. */
    fun covers(locale: String): Boolean = find(locale) != null

    /** Every language (and the one regional exception) the table has rules for. */
    fun coveredLanguages(): Set<String> = rules.keys

    private fun ruleFor(locale: String): Rule = find(locale) ?: unknownLanguage

    /** Looks up the whole tag first (`pt-PT`), then drops subtags from the right. */
    private fun find(locale: String): Rule? {
        var tag = LocaleTags.canonicalize(locale).lowercase(java.util.Locale.ROOT)
        while (tag.isNotEmpty()) {
            rules[tag]?.let { return it }
            val dash = tag.lastIndexOf('-')
            if (dash < 0) return null
            tag = tag.substring(0, dash)
        }
        return null
    }
}
