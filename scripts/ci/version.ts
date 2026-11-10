import { execFileSync } from 'node:child_process'
import fs from 'node:fs/promises'

// main APK is the pluginless build, so its version props come from that worktree; a plugins-only
// build has no pluginless worktree, and both worktrees carry the same props
async function worktreeWithProps(): Promise<string> {
  for (const dir of ['worktree-pluginless', 'worktree']) {
    try {
      await fs.access(`${dir}/gradle.properties`)
      return dir
    } catch {}
  }
  throw new Error('no worktree with gradle.properties found, run `bun run setup` first')
}
const worktree = await worktreeWithProps()
const props = await fs.readFile(`${worktree}/gradle.properties`, 'utf8')
const appVerName = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
if (!appVerName) throw new Error('failed to read APP_VERSION_NAME')
const appVerCode = /^APP_VERSION_CODE=(\d+)$/m.exec(props)?.[1]
if (!appVerCode) throw new Error('failed to read APP_VERSION_CODE')

const sha = process.env.GITHUB_SHA ?? ''
const shortSha = sha.slice(0, 7)
const verName = `${appVerName}-${shortSha}`

// apk.yml renders the boolean-vs-string workflow_dispatch input to a plain 'true'/'false'
// string before it reaches this env var -- see the LAST_UPLOADED_SHA comment in apk.yml for
// why the raw input can't be compared directly.
const isPrerelease = process.env.PRERELEASE === 'true'

const now = new Date()
const y4 = String(now.getUTCFullYear())
const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
const dd = String(now.getUTCDate()).padStart(2, '0')
const date = `${y4}${mm}${dd}`
const yyyymmdd = Number(date)

// dailyCounter = how many releases have already shipped today (0-indexed) -- a real same-day
// release slot: 00, 01, 02, ... Resets to 0 when `date` changes.
//
// entiny: the counter is derived from the release tags that already exist, because the
// "Bump build variables" step needs secrets.INU_VARS_TOKEN, which a fork does not have -- and
// continue-on-error hides that failure, so every same-day build got the same versionCode and
// the in-app updater ignored it. INU_DAY_STATE is kept as a manual floor: it can only push the
// counter forward, never back, so a stale variable cannot collide with a shipped tag.
function shippedToday(): number {
  let next = 0
  try {
    const prefix = `refs/tags/v${appVerName}-${date}`
    const refs = execFileSync('git', ['ls-remote', '--tags', 'origin', `${prefix}*`], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    const re = new RegExp(`${prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\d)$`)
    for (const line of refs.split('\n')) {
      const m = re.exec(line.trim())
      if (m) next = Math.max(next, Number(m[1]) + 1)
    }
  } catch (e) {
    console.warn(`could not list release tags, falling back to INU_DAY_STATE: ${e}`)
  }
  return next
}

const dayState = process.env.INU_DAY_STATE ?? ''
const [dayStateDate, dayStateCounterRaw] = dayState.split(':')
const manualCounter = dayStateDate === date ? Number(dayStateCounterRaw ?? '0') : 0
const dailyCounter = Math.max(shippedToday(), Number.isFinite(manualCounter) ? manualCounter : 0)
if (!Number.isInteger(dailyCounter) || dailyCounter < 0) {
  throw new Error(`invalid INU_DAY_STATE: ${dayState}`)
}
if (dailyCounter > 9) {
  throw new Error(`10+ releases already shipped today (dailyCounter=${dailyCounter}) -- versionCode slot exhausted, wait for UTC midnight`)
}

// tag/release-list id: YYYYMMDD + dailyCounter, e.g. 202608270, 202608271, ...
const tag = `v${appVerName}-${date}${dailyCounter}`

// versionCode = YYYYMMDD (8 digits) * 100 + dailyCounter(1 digit) * 10 -- same versionCode is
// shared by the optional arm7 APK (same release, different ABI), so the trailing digit stays
// a spare digit, not an ABI flag. 10 digits total, stays under Play's 2.1B ceiling until 2100.
const verCode = yyyymmdd * 100 + dailyCounter * 10

const out = {
  'app-ver-name': appVerName,
  'app-ver-code': appVerCode,
  'ver-name': verName,
  'ver-code': String(verCode),
  'day-counter': String(dailyCounter),
  date,
  // the in-app updater (UpdateHelper.kt) parses the versionCode straight out of the
  // filename -- it must not re-derive the date-based formula on-device. Its APK_RE regex
  // tolerates the optional "-beta" segment inserted here for pre-release builds, and only
  // ever matches "-arm64-" -- the arm7 build is intentionally invisible to the auto-updater.
  'apk-arm64': `entinygram${isPrerelease ? '-beta' : ''}-arm64-${appVerName}-${verCode}.apk`,
  'apk-arm7': `entinygram${isPrerelease ? '-beta' : ''}-armeabi-v7a-${appVerName}-${verCode}.apk`,
  // the updater only offers "-plugins-" APKs to plugins builds, and pluginless APKs to everyone else.
  'apk-plugins': `entinygram${isPrerelease ? '-beta' : ''}-plugins-arm64-${appVerName}-${verCode}.apk`,
  'apk-arm7-plugins': `entinygram${isPrerelease ? '-beta' : ''}-plugins-armeabi-v7a-${appVerName}-${verCode}.apk`,
  tag,
}

const githubOutput = process.env.GITHUB_OUTPUT
if (githubOutput) {
  const lines = `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`
  await fs.appendFile(githubOutput, lines)
}

console.log(JSON.stringify(out, null, 2))
