// Scripted player for the real-server integration harness (scripts/integration/README.md).
//
// A thin actuator: the Python runner owns every decision and every console command. This process
// joins as an ordinary offline-mode player and performs the actions only a client can, reading one
// JSON request per line on stdin and answering one JSON line on stdout:
//
//   {"id": 1, "op": "hold", "item": "end_crystal"}   ->   {"id": 1, "ok": true, ...}
//
// Unsolicited lines carry "event" instead of "id". Anything else it prints goes to stderr.
//
//   node client.js --host 127.0.0.1 --port 25599 --username Probe --version 1.21.11
'use strict'

const readline = require('readline')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

function option (name, fallback) {
  const at = process.argv.indexOf(`--${name}`)
  return at >= 0 && at + 1 < process.argv.length ? process.argv[at + 1] : fallback
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const send = (message) => process.stdout.write(JSON.stringify(message) + '\n')
const note = (...parts) => process.stderr.write(parts.join(' ') + '\n')

const bot = mineflayer.createBot({
  host: option('host', '127.0.0.1'),
  port: Number(option('port', '25565')),
  username: option('username', 'Probe'),
  version: option('version', false),
  auth: 'offline'
})

bot.on('kicked', (reason) => { send({ event: 'kicked', reason: JSON.stringify(reason) }); process.exit(3) })
bot.on('error', (error) => { send({ event: 'error', message: error.message }); process.exit(4) })
bot.on('end', (reason) => { send({ event: 'end', reason: String(reason) }); process.exit(0) })
bot.on('messagestr', (message) => note('CHAT', message))
bot.once('spawn', () => send({ event: 'spawn', version: bot.version }))

function itemCount (name) {
  return bot.inventory.items().filter((item) => item.name === name).reduce((sum, item) => sum + item.count, 0)
}

function nearest (name) {
  return bot.nearestEntity((entity) => entity.name === name)
}

const ops = {
  async state () {
    const p = bot.entity.position
    return {
      position: [p.x, p.y, p.z],
      dimension: bot.game.dimension,
      gameMode: bot.game.gameMode,
      health: bot.health,
      held: bot.heldItem ? bot.heldItem.name : null
    }
  },

  async count ({ item }) {
    return { count: itemCount(item) }
  },

  // Holds the named item, or an empty hand when item is null.
  async hold ({ item }) {
    if (item) {
      const stack = bot.inventory.items().find((candidate) => candidate.name === item)
      if (!stack) throw new Error(`no ${item} in the inventory`)
      await bot.equip(stack, 'hand')
    } else {
      const slots = bot.inventory.slots
      const empty = [0, 1, 2, 3, 4, 5, 6, 7, 8].find((slot) => !slots[bot.inventory.hotbarStart + slot])
      if (empty === undefined) throw new Error('no empty hotbar slot')
      bot.setQuickBarSlot(empty)
    }
    // A fresh selection resets the attack cooldown; wait it out so a hit is a full-strength one.
    await sleep(1200)
    return { held: bot.heldItem ? bot.heldItem.name : null }
  },

  // Names of the blocks in one column, bottom to top, as this client sees them.
  async column ({ x, z, ymin, ymax }) {
    const names = []
    for (let y = ymin; y <= ymax; y++) {
      const block = bot.blockAt(new Vec3(x, y, z))
      names.push(block ? block.name : null)
    }
    return { names }
  },

  // Right-clicks a block face with whatever is in the hand: places a crystal, wakes a bed.
  async use_block ({ x, y, z, face }) {
    const block = bot.blockAt(new Vec3(x, y, z))
    if (!block) throw new Error(`block ${x},${y},${z} is not loaded`)
    const faces = {
      up: new Vec3(0, 1, 0),
      down: new Vec3(0, -1, 0),
      north: new Vec3(0, 0, -1),
      south: new Vec3(0, 0, 1),
      west: new Vec3(-1, 0, 0),
      east: new Vec3(1, 0, 0)
    }
    await bot.activateBlock(block, faces[face || 'up'])
    await sleep(500)
    return { block: block.name }
  },

  // One melee hit. offset targets a dragon part: its entity id is the dragon's plus one (head)
  // to eight, in vanilla's part order, and the server resolves it with getEntityOrPart.
  async attack ({ entity, offset }) {
    const target = nearest(entity)
    if (!target) throw new Error(`no ${entity} in view`)
    await bot.lookAt(target.position.offset(0, 1, 0), true)
    bot.attack({ id: target.id + (offset || 0) })
    await sleep(300)
    return { target: target.id + (offset || 0), distance: bot.entity.position.distanceTo(target.position) }
  },

  // Waits out the fall the runner started with a teleport, then hits the target while still in
  // the air within trigger blocks of floor, so the server sees a Mace smash.
  async smash ({ entity, offset, floor, trigger, limit }) {
    const target = nearest(entity)
    if (!target) throw new Error(`no ${entity} in view`)
    const top = bot.entity.position.y
    const deadline = Date.now() + (limit || 10000)
    await new Promise((resolve, reject) => {
      const tick = () => {
        const y = bot.entity.position.y
        if (!bot.entity.onGround && y - floor <= trigger && top - y > 3) {
          bot.removeListener('physicsTick', tick)
          bot.attack({ id: target.id + (offset || 0) })
          resolve()
        } else if (bot.entity.onGround && top - y > 3) {
          bot.removeListener('physicsTick', tick)
          reject(new Error('landed before the hit'))
        } else if (Date.now() > deadline) {
          bot.removeListener('physicsTick', tick)
          reject(new Error('fall timed out'))
        }
      }
      bot.on('physicsTick', tick)
    })
    const hitAt = bot.entity.position.y
    await sleep(1500)
    return { fell: top - hitAt, hitY: hitAt }
  },

  async quit () {
    setTimeout(() => process.exit(0), 500)
    bot.quit()
    return {}
  }
}

readline.createInterface({ input: process.stdin }).on('line', async (line) => {
  let request
  try {
    request = JSON.parse(line)
  } catch (error) {
    note('unparseable request', line)
    return
  }
  const handler = ops[request.op]
  try {
    if (!handler) throw new Error(`unknown op ${request.op}`)
    send({ id: request.id, ok: true, ...(await handler(request)) })
  } catch (error) {
    send({ id: request.id, ok: false, error: error.message })
  }
})

// stdin closing means the runner is gone: never outlive it.
process.stdin.on('end', () => {
  try { bot.quit() } finally { setTimeout(() => process.exit(0), 500) }
})
