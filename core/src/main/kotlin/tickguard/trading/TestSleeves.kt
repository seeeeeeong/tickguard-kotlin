package tickguard.trading

import tickguard.stream.Decimal
import java.time.Instant

/**
 * The three-month test the user started on 2026-09-25: 210,000, 60,000 and
 * 30,000 won in an index core, Faber's timing model and concentrated
 * large-cap momentum. Fixed in code, not configuration: it is one experiment,
 * and changing it midway would be a different one. Modes are configuration.
 */
object TestSleeves {
    /** ECB's USD/KRW reference for 2026-09-24, fixing each sleeve's capital in dollars when the test began. */
    val FX: Decimal = Decimal.parse("1368.6", "fx")

    /** 22:00 KST on 2026-09-25, before the first orders: anything earlier is not the test's. */
    val START: Instant = Instant.parse("2026-09-25T13:00:00Z")

    /** Held by the account before the test. A manual order on one of these is the sleeve's only when tagged. */
    val PERSONAL: Set<String> = setOf("AMZN", "GOOGL", "SPCX")

    /** Faber's five asset classes. */
    private val FABER = listOf("VTI", "VEU", "IEF", "VNQ", "DBC")

    /**
     * Large caps across sectors, the user's own holdings included. SPCX is
     * skipped until it has six months of history.
     */
    private val MOMENTUM =
        listOf(
            "AAPL",
            "MSFT",
            "NVDA",
            "META",
            "AVGO",
            "TSLA",
            "BRK.B",
            "JPM",
            "LLY",
            "V",
            "WMT",
            "ORCL",
            "NFLX",
            "COST",
            "XOM",
            "AMZN",
            "GOOGL",
            "SPCX",
        )

    /** Where the dip sleeve's idle money waits, and the series its closes are checked by. */
    const val PARKING = "SPY"

    /** The dip sleeve's capital: the account's dollars on 2026-09-25, as the user set it. Code, not configuration. */
    val DIP_CAPITAL: Decimal = Decimal.parse("733.98", "dip capital")

    /**
     * The momentum sleeve's large caps without the user's own holdings
     * (AMZN, GOOGL, SPCX): the account holds those beside any sleeve, so a
     * sleeve's shares of them could not be checked against the account's.
     * SPY is where the sleeve's idle money waits.
     */
    private val DIP_SYMBOLS = MOMENTUM - PERSONAL

    private fun won(amount: Long) = Decimal.of(amount) / FX

    /** Vanguard's five-point threshold, for every sleeve. */
    private val BAND = Decimal.parse("0.05", "band")

    /** The sleeves, in the order they are reported. */
    val ALL: List<Sleeve> =
        listOf(
            Sleeve(
                "A",
                "A 코어",
                won(KRW_A),
                listOf("SPY", "QQQ", "TLT", "GLD", "EFA"),
                ::BuyAndHold,
                BAND,
                Decimal.parse("0.25", "loss"),
                LossAction.STOP_BUYING,
            ),
            Sleeve(
                "B",
                "B 추세",
                won(KRW_B),
                FABER + "SGOV",
                { FaberTiming(FABER, cash = "SGOV") },
                BAND,
                Decimal.parse("0.20", "loss"),
                LossAction.STOP_BUYING,
            ),
            Sleeve(
                "C",
                "C 모멘텀",
                won(KRW_C),
                MOMENTUM,
                { MomentumRotation(every = 1) },
                BAND,
                Decimal.parse("0.30", "loss"),
                LossAction.STOP_SLEEVE,
            ),
            Sleeve(
                "D",
                "D 반등형",
                DIP_CAPITAL,
                DIP_SYMBOLS + PARKING,
                ::BuyAndHold,
                BAND,
                Decimal.parse("0.40", "loss"),
                LossAction.STOP_BUYING,
                DipRules(parking = PARKING),
            ),
        )
}

/** Sleeve A's capital in won. */
private const val KRW_A = 210_000L

/** Sleeve B's capital in won. */
private const val KRW_B = 60_000L

/** Sleeve C's capital in won. */
private const val KRW_C = 30_000L
