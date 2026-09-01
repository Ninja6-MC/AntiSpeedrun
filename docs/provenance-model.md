# Decision Record: Item Provenance Model

> **Status:** Accepted
> **Date:** 2026-09-02
> **Issue:** [#52](https://github.com/Ninja6-MC/AntiSpeedrun/issues/52)
> **Supersedes:** the mixed provenance rules previously described in `ANTISPEEDRUN_SPECIFICATION.md` §2.3
> **Audit findings resolved:** C-01, C-02, R-04, R-10, and consequentially R-06, R-13

---

## Decision

**Item gating is a pure function of `(player advancements, material)`.** Nothing else.

There is no natural-origin provenance system, no finder entitlement, and no ownership
stamping on block drops. The single exception is a deliberately fragile death-and-drop
recall described in §4 below.

---

## Context

The specification originally described gating in two incompatible ways in the same
section: *material-based* ("can this player hold a diamond sword?") and *provenance-based*
("did this player earn this particular item?"). The engine was built material-based, while
the natural-structure exemption and the owner-recall privilege were written
provenance-based. Four audit findings turned out to be that one contradiction, viewed from
four angles:

| Finding | Symptom |
| :--- | :--- |
| **C-01** | The natural-origin exemption was written against the *tag*, not the *finder*. ItemStack PDC persists in NBT forever, so one player looting one dungeon chest could hand fully-exempt end-game gear to a day-one player. With `gate-natural-structure-chests: false` shipped as the default, that was the common case, not an edge case. |
| **C-02** | `BlockDropItemEvent` stamped the *breaking* player as owner, and owner-recall then granted unconditional retrieval. A booster placed a filled chest, the recipient broke it rather than opening it, and recall handed over the contents. Decorated Pots made it one block and no tools. |
| **R-04** | Under material gating, the hopper-siphoning task described an exploit that does not exist — moving diamonds between containers leaves them diamonds. Under provenance gating it would have. Nothing else in the epic was provenance-based. |
| **R-10** | Entity PDC and stack PDC were used interchangeably despite having completely different lifetimes. Entity PDC dies on stack merge, on pickup, and on despawn; stack PDC survives transfer but dies on craft and smelt. |

---

## The property that makes material-only gating sufficient

Each tier's prerequisite advancement is **strictly upstream of the tool required to obtain
that tier's items**:

| Tier | Unlocked by | Tool needed to obtain its items | Already unlocked? |
| :--- | :--- | :--- | :---: |
| iron | `story/mine_stone` | stone pickaxe (mining stone earns the advancement) | yes |
| diamond | `story/smelt_iron` | iron pickaxe | yes |
| nether | `story/smelt_iron` | obsidian access | yes |
| netherite | `story/mine_diamond` + `nether/obtain_blaze_rod` | diamond pickaxe, Nether access | yes |
| end | `nether/obtain_blaze_rod` + `nether/find_fortress` | blaze powder | yes |

A player following the natural progression **never encounters a lock**. The gate fires only
when someone skips ahead, which is the plugin's entire purpose.

This is the load-bearing claim of this decision. It means the false-positive problem that
owner-recall, `BlockDropItemEvent` stamping, and much of `exclude-materials` existed to
solve is prevented by the tier design itself rather than by runtime bookkeeping.

**If the tier prerequisites are ever retuned, re-verify this table.** A tier whose
prerequisite sits *downstream* of its own items reintroduces false positives, and this
decision would need revisiting.

---

## Why the natural-structure exemption was removed rather than fixed

This contradicts a stated design goal — *"natural dungeon chests are earned exploration"* —
so the reasoning is recorded in full.

Three implementations were considered and all three fail:

1. **Exempt by tag.** Any tag meaning "this item is exempt" travels with the item forever
   and becomes a laundering token. This is C-01 exactly.
2. **Exempt by finder UUID, stripping the tag on transfer.** Closes laundering, but creates
   a worse cliff: a player cannot stash their own legitimate find in their own chest and
   retrieve it later. The plugin confiscates loot the player earned.
3. **Exempt by finder UUID, never stripping.** No cliff, but it is a parallel per-item,
   per-player entitlement system living in NBT — the highest-risk possible location for
   correctness bugs and exploits, in a plugin budgeted at 1,200–1,800 LOC.

Container ownership was also considered — stamp a chest's `TileState` PDC with its placer
and allow unrestricted withdrawal by the owner. It fails directly: the recipient places the
chest and the booster fills it. Patching that requires deposit checks as well, and then
double-chest split ownership, shulker boxes, trades, and hoppers crossing ownership
boundaries. That is a land-claim plugin.

**The irreducible constraint:** *"my own above-tier loot that I stored"* and *"a booster's
above-tier gift"* cannot be distinguished without per-item ownership state. Either accept
that state and the exploit surface it brings, or accept that above-tier items cannot be
held. There is no third option.

The cost of choosing the latter is smaller than it first appears. The structures holding
above-tier loot that are reachable early are few — desert temples, shipwrecks, buried
treasure, mineshafts. Ancient Cities, Bastions, and End Cities cannot be survived or
reached below their tier regardless. And the loot is not destroyed: it stays in the chest,
and the player will qualify shortly.

If early-treasure retention matters more than this analysis assumes, the correct lever is
**retuning tier prerequisites**, not reintroducing an entitlement system.

---

## The PDC key register (authoritative)

Two keys survive. Any key not listed here does not exist.

| Key | Container | Lifetime | Owner | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `n6_asr_dropper` | **`Item` entity** | Dies on stack merge, on pickup, and on the ~5 minute despawn | UUID of the dropping player | Death and manual-drop recall (§4) |
| `n6_asr_secondary_dragon` | `EnderDragon` entity | Entity lifetime | — | Marks a plugin-spawned scaling dragon (Epic 6; unrelated to items) |

UUIDs are stored as `PersistentDataType.LONG_ARRAY` holding `[mostSigBits, leastSigBits]`
(16 bytes), never as `STRING` (36 bytes).

**No ItemStack ever carries a plugin tag.** This is the invariant that makes laundering
structurally impossible: there is no per-item entitlement to forge, transfer, or inherit.

---

## §4. The one retained provenance rule: death and drop recall

`PlayerDropItemEvent` and `PlayerDeathEvent` stamp `n6_asr_dropper` on the resulting `Item`
**entities**. A player may always re-collect an item entity carrying their own UUID,
regardless of tier.

`BlockDropItemEvent` **does not stamp anything.** This is what closes C-02: breaking a
container confers nothing on the breaker.

This rule exists for the genuine cases where a player legitimately holds above-tier items —
administrative grants, items predating installation, and gear held while a bypass
permission was active that was later revoked. Losing gear to a tier check on death would be
a real support burden.

It is safe *because* entity PDC is fragile. The short lifetime flagged as a defect in R-10
is the feature here:

- it cannot be transferred, because pickup destroys it;
- it cannot be stockpiled, because despawn destroys it;
- it cannot be diluted into a stack, because merging destroys it;
- it privileges exactly one player, for at most five minutes.

---

## Consequences

### Closed as unnecessary
- **#31** (Task 4.1.1, natural loot provenance engine) — nothing to tag. Also removes the
  unresolved Trial Vault hook problem (R-06), which had no verified API path.
- **#10** (Task 4.1.3, hopper siphoning) — the exploit does not exist under material gating,
  and `InventoryMoveItemEvent` fires roughly eight times per second per active hopper.
- **#53** (Task 4.2.4, container-break laundering) — no ownership stamping to abuse.

### Narrowed
- **#13** (Task 4.2.3) — death and manual drops only; `BlockDropItemEvent` stamping removed.
- **#17** (Task 4.3.4) — mob-pickup cancellation removed. A zombie holding a diamond sword
  is harmless, because the unqualified player still cannot pick it up when the zombie dies.
  This also resolves R-13: piglin bartering and Allay sorters are no longer affected.
  Dragon's Breath bottling remains.
- **#9** (Task 4.1.2) — a flat material check on withdrawal. The natural-versus-player
  container distinction (`lootTable == null`) is gone.

### Unaffected
**#54** (merchants), **#55** (1.21 coverage), **#14** (dispenser armor), **#15** (bundles),
**#16** (armor stands) are all pure material checks and are unchanged by this decision.

### Configuration removed
`gate-natural-structure-chests`, `gate-player-placed-chests`, and `gate-armor-stands` are
deleted rather than re-defaulted — each configured a distinction that no longer exists.
`dropper-can-retrieve` and `death-drop-retrieval` collapse into a single
`drop-recall-enabled`.

---

## Open question deliberately left unresolved

Provenance does not survive crafting or smelting, because those produce a fresh `ItemStack`.
Under this model that is irrelevant — no `ItemStack` carries provenance at all — but it is
recorded here so that any future proposal to reintroduce stack tagging must address it
rather than rediscover it.
