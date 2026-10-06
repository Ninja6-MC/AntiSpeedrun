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
// Chat and action bar lines since the last "messages" request, for asserting what a player is told.
let heard = []
bot.on('messagestr', (message, position) => {
  note('CHAT', position, message)
  heard.push(message)
  if (heard.length > 200) heard = heard.slice(-200)
})
bot.on('actionBar', (message) => {
  const text = message.toString()
  note('ACTIONBAR', text)
  heard.push(text)
})
bot.once('spawn', () => send({ event: 'spawn', version: bot.version }))
bot.on('death', () => note('DEATH'))

// The container or merchant window the last open_block or open_merchant opened.
let window = null

function itemCount (name) {
  return bot.inventory.items().filter((item) => item.name === name).reduce((sum, item) => sum + item.count, 0)
}

// From 1.21.3 mineflayer reports sneaking only in player_input, but servers before 1.21.6 still
// take it from entity_action's start and stop sneaking, so on those the server never sees it.
function legacySneak (state) {
  if (bot.registry.version['>=']('1.21.6')) return
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: state ? 0 : 1, jumpBoost: 0 })
}

function nearest (name) {
  return bot.nearestEntity((entity) => entity.name === name)
}

const FACES = {
  up: new Vec3(0, 1, 0),
  down: new Vec3(0, -1, 0),
  north: new Vec3(0, 0, -1),
  south: new Vec3(0, 0, 1),
  west: new Vec3(-1, 0, 0),
  east: new Vec3(1, 0, 0)
}

function blockAt (x, y, z) {
  const block = bot.blockAt(new Vec3(x, y, z))
  if (!block) throw new Error(`block ${x},${y},${z} is not loaded`)
  return block
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

  // Every item the inventory holds, as { name: count }.
  async inventory () {
    const items = {}
    for (const item of bot.inventory.items()) items[item.name] = (items[item.name] || 0) + item.count
    return { items }
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
    const block = blockAt(x, y, z)
    await bot.activateBlock(block, FACES[face || 'up'])
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

  // Every chat and action bar line heard since the last call.
  async messages () {
    const lines = heard
    heard = []
    return { lines }
  },

  // A chat line or, with a leading slash, a command run as this player.
  async chat ({ text }) {
    bot.chat(text)
    await sleep(700)
    return {}
  },

  // Walks forward for ms milliseconds toward the point [x, y, z], sneaking with sneak and jumping
  // all the way with jump.
  async walk ({ toward, ms, sneak, jump }) {
    await bot.lookAt(new Vec3(toward[0], toward[1], toward[2]), true)
    if (sneak) {
      bot.setControlState('sneak', true)
      legacySneak(true)
      await sleep(300)
    }
    bot.setControlState('forward', true)
    if (jump) bot.setControlState('jump', true)
    await sleep(ms)
    bot.setControlState('forward', false)
    bot.setControlState('jump', false)
    bot.setControlState('sneak', false)
    if (sneak) legacySneak(false)
    await sleep(300)
    const p = bot.entity.position
    return { position: [p.x, p.y, p.z], dimension: bot.game.dimension }
  },

  // Boards the nearest entity whose name contains name, a boat or a minecart.
  async mount ({ name }) {
    const vehicle = bot.nearestEntity((entity) => entity.name && entity.name.includes(name))
    if (!vehicle) throw new Error(`no ${name} in view`)
    bot.mount(vehicle)
    for (let i = 0; i < 30 && !bot.vehicle; i++) await sleep(100)
    if (!bot.vehicle) throw new Error(`could not board the ${vehicle.name}`)
    return { vehicle: vehicle.name }
  },

  // Steers the vehicle the player rides by reporting its movement, as a client paddling a boat
  // does: steps moves of (dx, dz) each, step_ms apart, or until the dimension changes.
  async drive ({ dx, dz, steps, step_ms }) {
    if (!bot.vehicle) throw new Error('not riding anything')
    const start = bot.game.dimension
    const at = bot.vehicle.position.clone()
    let moved = 0
    for (; moved < steps && bot.game.dimension === start && bot.vehicle; moved++) {
      at.x += dx
      at.z += dz
      bot._client.write('vehicle_move', { x: at.x, y: at.y, z: at.z, yaw: 0, pitch: 0, onGround: true })
      await sleep(step_ms || 100)
    }
    return { moved, dimension: bot.game.dimension, riding: !!bot.vehicle }
  },

  async dismount () {
    if (bot.vehicle) bot.dismount()
    await sleep(500)
    return { riding: !!bot.vehicle }
  },

  // Opens the container block at x, y, z and lists what the client sees in it.
  // A furnace opens through openBlock: openContainer takes storage blocks only.
  async open_block ({ x, y, z }) {
    const block = blockAt(x, y, z)
    window = block.name.endsWith('furnace') ? await bot.openBlock(block) : await bot.openContainer(block)
    return { slots: window.containerItems().map((item) => ({ slot: item.slot, name: item.name, count: item.count })) }
  },

  // Opens the nearest merchant of the named entity type and lists its offers.
  async open_merchant ({ entity }) {
    const target = nearest(entity)
    if (!target) throw new Error(`no ${entity} in view`)
    window = await bot.openVillager(target)
    for (let i = 0; i < 30 && !window.trades; i++) await sleep(100)
    return { trades: (window.trades || []).map((trade) => trade.outputItem && trade.outputItem.name) }
  },

  // Selects offer index and takes its result the way a player does: select, then shift-click
  // the result slot. The server's answer is read from the inventory afterwards, not from here.
  async trade ({ index }) {
    if (!window) throw new Error('no merchant window open')
    bot._client.write('select_trade', { slot: index })
    await sleep(600)
    const result = window.slots[2]
    try {
      await bot.clickWindow(2, 0, 1)
    } catch (error) {
      note('trade click', error.message)
    }
    await sleep(600)
    return { result: result ? result.name : null }
  },

  // A click on a slot of the open window: mode 0 is a pickup click, 1 a shift-click.
  async window_click ({ slot, button, mode }) {
    if (!window) throw new Error('no window open')
    try {
      await bot.clickWindow(slot, button || 0, mode || 0)
    } catch (error) {
      note('window click', error.message)
    }
    await sleep(600)
    // Put back whatever the cursor holds, so a refused click leaves nothing in hand.
    if (window.selectedItem) {
      try {
        await bot.clickWindow(-999, 0, 0)
      } catch (error) {
        note('cursor return', error.message)
      }
    }
    return {
      slots: window.containerItems().map((item) => ({ slot: item.slot, name: item.name, count: item.count }))
    }
  },

  // Moves count (all when omitted) of the named item from the player's part of the open window
  // into slot, as a player does: pick the stack up, then a left click (all) or right clicks (one
  // each) on the slot. Whatever is left on the cursor goes back where it came from.
  async window_load ({ item, slot, count }) {
    if (!window) throw new Error('no window open')
    const type = bot.registry.itemsByName[item]
    if (!type) throw new Error(`unknown item ${item}`)
    const stack = window.findInventoryItem(type.id, null)
    if (!stack) throw new Error(`no ${item} in the inventory`)
    const from = stack.slot
    await bot.clickWindow(from, 0, 0)
    if (count) {
      for (let i = 0; i < count; i++) await bot.clickWindow(slot, 1, 0)
    } else {
      await bot.clickWindow(slot, 0, 0)
    }
    if (window.selectedItem) await bot.clickWindow(from, 0, 0)
    await sleep(600)
    return {
      slots: window.containerItems().map((entry) => ({ slot: entry.slot, name: entry.name, count: entry.count }))
    }
  },

  async close_window () {
    if (window) bot.closeWindow(window)
    window = null
    await sleep(500)
    return {}
  },

  // Places the held block against the face of the block at x, y, z.
  async place ({ x, y, z, face }) {
    const reference = blockAt(x, y, z)
    await bot.lookAt(reference.position.offset(0.5, 0.5, 0.5), true)
    await bot.placeBlock(reference, FACES[face || 'up'])
    await sleep(500)
    return { placed: bot.blockAt(reference.position.plus(FACES[face || 'up'])).name }
  },

  // Crafts count of the named item in the crafting table at table [x, y, z], from what the
  // inventory holds, by clicking the grid and the result slot as a player does. mineflayer's
  // view of the grid can fall behind the server's, on Folia above all, and the craft then stops
  // short; closing the table hands the grid back, so it tries again, up to three times.
  async craft ({ item, count, table }) {
    const type = bot.registry.itemsByName[item]
    if (!type) throw new Error(`unknown item ${item}`)
    const bench = table ? blockAt(table[0], table[1], table[2]) : null
    const before = itemCount(item)
    for (let attempt = 1; attempt <= 3; attempt++) {
      const recipes = bot.recipesFor(type.id, null, 1, bench)
      if (!recipes.length) throw new Error(`no recipe for ${item} from this inventory`)
      try {
        await bot.craft(recipes[0], count || 1, bench)
      } catch (error) {
        note('craft attempt', attempt, error.message)
      }
      await sleep(800)
      if (bot.currentWindow) {
        bot.closeWindow(bot.currentWindow)
        await sleep(800)
      }
      if (itemCount(item) > before) return { count: itemCount(item), attempts: attempt }
    }
    return { count: itemCount(item), attempts: 3 }
  },

  // Uses the held item on the block at x, y, z and keeps using it for ms milliseconds, looking at
  // the block, as a player holding the use key does: brushing a suspicious block.
  async brush ({ x, y, z, face, ms }) {
    const block = blockAt(x, y, z)
    const target = block.position.offset(0.5, 0.5, 0.5).plus(FACES[face || 'up'].scaled(0.5))
    await bot.lookAt(target, true)
    await bot.activateBlock(block, FACES[face || 'up'])
    await sleep(ms || 8000)
    bot.deactivateItem()
    await sleep(300)
    return { block: bot.blockAt(block.position).name }
  },

  // Breaks the block at x, y, z with whatever is in the hand.
  // With ms, digs for that long by sending the start and finish packets itself: mineflayer cannot
  // work out the dig time of an enchanted tool through ViaVersion.
  async dig ({ x, y, z, ms }) {
    const block = blockAt(x, y, z)
    await bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true)
    if (ms) {
      bot._client.write('block_dig', { status: 0, location: block.position, face: 1 })
      for (let waited = 0; waited < ms; waited += 250) {
        bot.swingArm()
        await sleep(250)
      }
      bot._client.write('block_dig', { status: 2, location: block.position, face: 1 })
    } else {
      await bot.dig(block, true)
    }
    await sleep(500)
    return { block: block.name }
  },

  // Throws count of the named item out of the inventory.
  async toss ({ item, count }) {
    const stack = bot.inventory.items().find((candidate) => candidate.name === item)
    if (!stack) throw new Error(`no ${item} in the inventory`)
    await bot.toss(stack.type, null, count || 1)
    await sleep(300)
    return { left: itemCount(item) }
  },

  async respawn () {
    if (bot.health <= 0 || bot.isAlive === false) bot.respawn()
    for (let i = 0; i < 50 && bot.health <= 0; i++) await sleep(100)
    await sleep(1000)
    return { health: bot.health }
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
