/**
 * The original's side of the parity harness: runs the TypeScript replay and
 * writes every fire as one tab-separated line, for the Kotlin replay to be
 * compared against byte for byte.
 *
 * Runs the original's own code, imported from its checkout, so the golden is
 * what the original computes rather than a description of it:
 *
 *   TICKGUARD_TS=../tickguard node tools/parity/replay-golden.ts synthetic core/src/test/resources/parity
 *   TICKGUARD_TS=../tickguard node tools/parity/replay-golden.ts db ../tickguard/tickguard.db > eval/parity/golden.tsv
 *
 * `synthetic` makes up two symbols' ticks and writes them beside the fires;
 * that pair is committed and checked in CI. `db` replays a real database,
 * read-only; its output names held symbols and stays under eval/, which is
 * gitignored.
 */
import { writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { pathToFileURL } from 'node:url'

const root = resolve(process.env.TICKGUARD_TS ?? '../tickguard')
const load = (path) => import(pathToFileURL(join(root, path)).href)
const { replay, rapidMoveCase, drawdownCase, DEFAULT_HORIZONS_MS } = await load('src/backtest/replay.ts')
const { toDecimal } = await load('src/stream/money.ts')

const MINUTE = 60_000

/** The same list as ParityCases.kt. Changing one without the other fails the comparison, as it should. */
const cases = () => [
  ...['0.5', '1', '2', '3'].map((percent) => rapidMoveCase(percent, 5 * MINUTE)),
  ...['1', '2', '3', '5'].map((percent) => drawdownCase(percent, 2 * MINUTE)),
]

const cell = (value) => (value === undefined ? '-' : value.toFixed())

function firesOf(ticks) {
  const lines = []
  const byCode = Map.groupBy(ticks, (tick) => tick.code)
  for (const [code, rows] of byCode) {
    // Averages are not recorded historically. The first price stands in, on both sides.
    const positions = new Map([
      [code, { code, averagePrice: toDecimal(rows[0].price, 'a'), quantity: toDecimal('0', 'q') }],
    ])
    for (const backtest of cases()) {
      for (const fire of replay(backtest, rows, { positions })) {
        const after = DEFAULT_HORIZONS_MS.map((horizon) => cell(fire.after.get(horizon)))
        lines.push([fire.case, fire.code, fire.at, fire.direction, fire.price.toFixed(), fire.title, ...after].join('\t'))
      }
    }
  }
  return lines.map((line) => `${line}\n`).join('')
}

/** A random walk with occasional jumps, so every case fires. Seeded, so it is the same walk each time. */
function randomWalk(seed, count, code) {
  let state = seed
  const random = () => {
    state = (state * 1_103_515_245 + 12_345) % 2_147_483_648
    return state / 2_147_483_648
  }
  let price = 100
  const t0 = Date.parse('2026-09-23T13:30:00Z')
  return Array.from({ length: count }, (_, i) => {
    price *= 1 + (random() - 0.5) * 0.004 + (random() < 0.01 ? (random() - 0.5) * 0.08 : 0)
    // Several prints can share a second on the wire; every fifth one repeats its stamp.
    const at = t0 + (i - (i % 5 === 4 ? 1 : 0)) * 15_000
    return { type: 'trade:us', code, price: price.toFixed(2), volume: '1', currency: 'USD', tradedAt: at, receivedAt: at }
  })
}

const [mode, target] = process.argv.slice(2)
if (mode === 'synthetic') {
  const ticks = [...randomWalk(42, 2_000, 'FAKEA'), ...randomWalk(7, 2_000, 'FAKEB')]
  const columns = ['type', 'code', 'price', 'volume', 'currency', 'tradedAt', 'receivedAt']
  writeFileSync(join(target, 'ticks.tsv'), ticks.map((t) => `${columns.map((c) => t[c]).join('\t')}\n`).join(''))
  writeFileSync(join(target, 'fires.tsv'), firesOf(ticks))
} else if (mode === 'db') {
  const db = new DatabaseSync(target, { readOnly: true })
  const ticks = db
    .prepare(
      `SELECT type, code, price, volume, currency, traded_at AS tradedAt, received_at AS receivedAt
       FROM ticks ORDER BY code, traded_at, rowid`,
    )
    .all()
  process.stdout.write(firesOf(ticks))
} else {
  console.error('usage: replay-golden.ts synthetic <dir> | db <tickguard.db>')
  process.exit(1)
}
