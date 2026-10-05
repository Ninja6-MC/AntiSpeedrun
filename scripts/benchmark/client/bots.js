// Fighting party for the max-dragons benchmark (scripts/benchmark/README.md).
//
// Joins --count offline-mode players named <prefix>1..<prefix>N. Once in the End, each one engages
// the nearest Ender Dragon on its own: a melee hit when one is within reach, otherwise a bow shot at
// it. With --passive they join and stand still. The Python runner owns every console command
// (teleports, items, effects); this process only plays and observes.
//
// Requests arrive one JSON line at a time on stdin and are answered with one JSON line on stdout:
//
//   {"id": 1, "op": "state"}                                 every bot's dimension, position, health
//   {"id": 2, "op": "column", "x": 1, "z": 0, "ymin": 40, "ymax": 90}   block names, as the first bot sees them
//   {"id": 3, "op": "bossbars"}                              the boss bars the first bot is shown
//
// Unsolicited lines carry "event" instead of "id". Anything else goes to stderr.
//
//   node bots.js --host 127.0.0.1 --port 25599 --version 26.1 --count 5 --prefix Bench [--passive]
'use strict'

const readline = require('readline')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

function option (name, fallback) {
  const at = process.argv.indexOf(`--${name}`)
  return at >= 0 && at + 1 < process.argv.length ? process.argv[at + 1] : fallback
}

const host = option('host', '127.0.0.1')
const port = Number(option('port', '25565'))
const version = option('version', false)
const count = Number(option('count', '5'))
const prefix = option('prefix', 'Bench')
const passive = process.argv.includes('--passive')

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const send = (message) => process.stdout.write(JSON.stringify(message) + '\n')
const note = (...parts) => process.stderr.write(parts.join(' ') + '\n')

const MELEE_REACH = 5
const BOW_RANGE = 64

function nearestDragon (bot) {
  return bot.nearestEntity((entity) => entity.name === 'ender_dragon')
}

async function engage (bot) {
  while (true) {
    await sleep(250)
    if (!bot.entity || bot.game.dimension !== 'the_end' || bot.health <= 0) continue
    const dragon = nearestDragon(bot)
    if (!dragon) continue
    const distance = bot.entity.position.distanceTo(dragon.position)
    try {
      if (distance <= MELEE_REACH) {
        await bot.lookAt(dragon.position.offset(0, 1, 0), true)
        bot.attack(dragon)
        bot.stats.melee++
        await sleep(600)
      } else if (distance <= BOW_RANGE) {
        const bow = bot.inventory.items().find((item) => item.name === 'bow')
        if (!bow) continue
        if (!bot.heldItem || bot.heldItem.name !== 'bow') await bot.equip(bow, 'hand')
        await bot.lookAt(dragon.position.offset(0, 2, 0), true)
        bot.activateItem()
        await sleep(1100)
        const target = nearestDragon(bot)
        if (target) await bot.lookAt(target.position.offset(0, 2, 0), true)
        bot.deactivateItem()
        bot.stats.shots++
      }
    } catch (error) {
      note(bot.username, 'engage error', error.message)
    }
  }
}

function join (index) {
  const username = `${prefix}${index}`
  const bot = mineflayer.createBot({ host, port, username, version, auth: 'offline' })
  bot.stats = { shots: 0, melee: 0, deaths: 0 }
  bot.on('kicked', (reason) => send({ event: 'kicked', bot: username, reason: JSON.stringify(reason) }))
  bot.on('error', (error) => send({ event: 'error', bot: username, message: error.message }))
  bot.on('end', (reason) => send({ event: 'end', bot: username, reason: String(reason) }))
  bot.on('death', () => { bot.stats.deaths++; send({ event: 'death', bot: username }) })
  bot.on('messagestr', (message) => note(username, 'CHAT', message))
  bot.once('spawn', () => {
    send({ event: 'spawn', bot: username, version: bot.version })
    if (!passive) engage(bot)
  })
  return bot
}

const bots = []

function describeBar (bar) {
  const title = bar.title && typeof bar.title.toString === 'function' ? bar.title.toString() : String(bar.title)
  return { title, health: bar.health, dragonBar: Boolean(bar.isDragonBar) }
}

const ops = {
  async state () {
    return {
      bots: bots.map((bot) => ({
        name: bot.username,
        dimension: bot.game ? bot.game.dimension : null,
        position: bot.entity ? [bot.entity.position.x, bot.entity.position.y, bot.entity.position.z] : null,
        health: bot.health,
        dragonsInView: bot.entities ? Object.values(bot.entities).filter((e) => e.name === 'ender_dragon').length : 0,
        stats: bot.stats
      }))
    }
  },

  async column ({ x, z, ymin, ymax }) {
    const names = []
    for (let y = ymin; y <= ymax; y++) {
      const block = bots[0].blockAt(new Vec3(x, y, z))
      names.push(block ? block.name : null)
    }
    return { names }
  },

  async bossbars () {
    return { bars: bots[0].bossBars.map(describeBar) }
  }
}

async function main () {
  for (let i = 1; i <= count; i++) {
    bots.push(join(i))
    // Staggered so no join lands inside the server's connection throttle.
    await sleep(1500)
  }
  readline.createInterface({ input: process.stdin }).on('line', async (line) => {
    let request
    try {
      request = JSON.parse(line)
    } catch (error) {
      note('unparseable request', line)
      return
    }
    try {
      const handler = ops[request.op]
      if (!handler) throw new Error(`unknown op ${request.op}`)
      send({ id: request.id, ok: true, ...(await handler(request)) })
    } catch (error) {
      send({ id: request.id, ok: false, error: error.message })
    }
  })
  // stdin closing means the runner is gone: never outlive it.
  process.stdin.on('end', () => {
    for (const bot of bots) {
      try { bot.quit() } catch (error) { note('quit', error.message) }
    }
    setTimeout(() => process.exit(0), 1000)
  })
}

main()
