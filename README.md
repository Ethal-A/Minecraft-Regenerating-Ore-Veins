# Regenerating Ore Veins

A configurable NeoForge mod for Minecraft `1.21.1` that allows you to generate ore veins that regenerate over time.



## Regenerator Block

This mod centres around the `regenerator block` which is a repurposed glass block. It is translucent and non-colliding and marks the location of an ore that will be regenerated after an interval. Each time the ore is destroyed, the `regenerator block` will replace it and begin the countdown until it generates the ore again.

Behavior of the `regenerator block`:

- players and entities can walk through it
- sneaking + right click shows the target block and remaining time
- creative players can sneak-right-click it to open a configuration screen
- the config screen sets target block, interval seconds, and jitter min/max seconds
- players in creative mode use middle-click to copy a regenerator block with its block entity data so it can be placed elsewhere
- players in creative mode can break regenerator blocks even when `allow_breaking` is false
- players in survival mode can break the block (if `allow_breaking` is set to true) by sneaking and mining the block
- breaking can be disabled entirely in `global.json` using the `allow_breaking` configuration option
- regenerator blocks ignore explosions by default, while managed ores destroyed by explosions still enter regeneration
- managed ores removed by modded block breakers are detected and moved into the regeneration cycle
- the regenerator block is tagged as non-movable/non-breakable for Create and non-movable for Create Aeronautics/Simulated contraptions
  - what this effectively means is that contraptions will move the ore but not the placement of the regenerator block and drills will destroy the ore but not the regenerator block
- regeneration timing is stored with persistent data, so unloaded chunks still count down
- when regeneration finishes, smoke particles are emitted and the target block is restored (this can be turned off in `global.json` using the `regeneration_smoke_particles` configuration option

### Setting A Manual Regenerator Interval In Game

In creative mode, sneak-right-click a regenerator block and edit:

- `Target block`, for example `minecraft:diamond_ore`
- `Interval seconds`, for example `3600`
- `Jitter min` and `Jitter max`, for example `-300` and `300`

The jitter is applied when the block enters its regenerating state. An interval of `3600` with jitter `-300..300` produces a real interval from `3300` to `3900` seconds.

For commands or automation, the same values are stored as block entity data:

```mcfunction
/data merge block <x> <y> <z> {IntervalSeconds:60,EffectiveIntervalSeconds:60,JitterRangeMinSeconds:-10,JitterRangeMaxSeconds:10}
```

Example for the block you are standing on:

```mcfunction
/data merge block ~ ~-1 ~ {IntervalSeconds:60,EffectiveIntervalSeconds:60,JitterRangeMinSeconds:-10,JitterRangeMaxSeconds:10}
```

`IntervalSeconds` is the base number of real-time seconds before that regenerator restores its target block. `EffectiveIntervalSeconds` is the currently active countdown value: set it too when editing with `/data`.

Global config values live in:

- `config/regenerating_ore_veins/global.json`

```json
{
  "allow_breaking": true,
  "break_hardness": 5.0,
  "default_regeneration_seconds": 3600,
  "default_jitter_interval": {
    "range_min": 0,
    "range_max": 0
  },
  "regeneration_smoke_particles": true,
  "destroyed_by_explosives": false,
  "default_fill_factor": 1.0
}
```

On a dedicated server or multiplayer world, the server's `global.json` is authoritative for gameplay behavior. A local client copy only affects that client's own singleplayer/integrated server and does not override the server.

Important values:

- `allow_breaking` defaults to `true`
- `break_hardness` defaults to `5.0`
- `default_regeneration_seconds` defaults to `3600` (1 hour)
- `default_jitter_interval` defaults to no jitter
- `regeneration_smoke_particles` defaults to `true`
- `destroyed_by_explosives` defaults to `false`: when false, explosions do not destroy regenerator blocks
- `default_fill_factor` defaults to `1.0`: used by veins that do not set their own `fill_factor`

## JSON Configuration

The mod creates these files under:

- `config/regenerating_ore_veins/areas.json`
- `config/regenerating_ore_veins/veins.json`
- `config/regenerating_ore_veins/global.json`
- `config/regenerating_ore_veins/vein_locator.json`

### `areas.json`
The following example shows how you can create an area (note this is heavily inspired by the mod, In Control!).

```json
[
  {
    "dimension": "minecraft:overworld",
    "name": "frontier_0",
    "type": "box",
    "x": 0,
    "y": 128,
    "z": 0,
    "dimx": 8192,
    "dimy": 512,
    "dimz": 8192
  }
]
```

`type` supports:

- `box`: rectangular prism using `dimx`, `dimy`, and `dimz`
- `circle`: horizontal ellipse/cylinder using `dimx` and `dimz` as diameters, plus `dimy` as the vertical height

### `veins.json`
The following example shows how you can configure a vein.

```json
[
  {
    "id": "gold_ore_unusual",
    "blocks": [
      "minecraft:gold_ore",
      "minecraft:deepslate_gold_ore",
      "minecraft:nether_gold_ore"
    ],
    "weights": [
      1,
      2,
      4
    ],
    "dimension": [
      "minecraft:overworld",
      "minecraft:the_nether"
    ],
    "biome": [
      "minecraft:badlands",
      "#c:is_jungle",
      "minecraft:nether_wastes"
    ],
    "area_whitelist": [
      "frontier_0"
    ],
    "area_blacklist": [],
    "shape": "sphere",
    "min_radius": 2,
    "max_radius": 3,
    "fill_factor": 1.0,
    "attempts": 1,
    "min_y": -64,
    "max_y": 32,
    "chunk_minimum_generation_separation": 0.5,
    "regeneration_interval_seconds": 3600,
    "regeneration_interval_jitter": {
      "range_min": -240,
      "range_max": 240
    }
  }
]
```

Supported/optional fields:

- `area_whitelist` accepts a string or list of area names. If provided, the vein only generates inside those areas.
- `area_blacklist` accepts a string or list of area names. If provided, the vein never generates inside those areas.
- `dimension` accepts a string or list and defaults to `minecraft:overworld`. The vanilla Nether id is `minecraft:the_nether`, though `minecraft:nether` is accepted as an alias.
- `biome` accepts a string or list. Entries can be exact biome ids such as `minecraft:plains` or tags prefixed with `#`, such as `#c:is_jungle`.
- `weights` defaults to `1` for each block
- `shape` supports `sphere` and `box`
- `min_radius` and `max_radius` choose a random radius in blocks for each vein. For `sphere`, this is the radius of a sphere-like vein. For `box`, this is the half-extent of a cube from the center.
- `fill_factor` is a `0.0..1.0` probability applied to each candidate block in the vein shape. `0` places none of the selected positions, `1` places all selected positions. Defaults to `global.json` value `default_fill_factor`.
- `chunk_minimum_generation_separation` defaults to `8`. Values `>= 1` space candidate chunks apart. Values below `1` multiply generation in each chunk, so `0.5` means two attempts per chunk and `0.25` means four.
- `regeneration_interval_seconds` defaults to `global.json` value `default_regeneration_seconds`
- `regeneration_interval_jitter` defaults to `global.json` value `default_jitter_interval`

If whitelist and blacklist overlap, `area_blacklist` wins. `dimension` is still applied when area filters are present, so admins can combine dimension, area, biome, and Y-level limits. The default `veins.json` examples are not area-constrained and include Overworld and Nether examples. Vanilla Minecraft does not have separate Overworld quartz ore or netherite ore blocks, so the default examples use `minecraft:quartz_block` for the quartz example and `minecraft:ancient_debris` for netherite.

Expected blocks generated with `fill_factor: 1.0`:

| Radius | `sphere` |  `box` |
| ---: | ---: |-------:|
| 1 | 7 |     27 |
| 2 | 33 |    125 |
| 3 | 123 |    343 |
| 6 | 925 |  2,197 |
| 12 | 7,153 | 15,625 |

With `fill_factor: 0.5`, you can expect the number of blocks to roughly half.

### `vein_locator.json`

```json
{
  "allow_crafting": true,
  "allow_use": true,
  "crafting_recipe": [
    "minecraft:netherite_ingot",
    "minecraft:ender_eye"
  ],
  "craft_any_order": true,
  "chance_to_break": 0.75,
  "use_durability": false,
  "durability": 2,
  "stack_size": 64
}
```

The `Vein Locator` is crafted from the configured recipe. Because the recipe is generated as a normal server datapack recipe, JEI can display it after recipes reload.

Tune a locator by crafting it with any block item. The output locator stores that block as its target. When used, it flies like an eye of ender toward the nearest tracked regenerating vein block in the current dimension with the same target block.

- `allow_crafting` controls the base locator recipe
- `allow_use` disables locator usage server-side
- `crafting_recipe` is a list of item ids. `minecraft:netherite` is accepted as an alias for `minecraft:netherite_ingot`.
- `craft_any_order` uses a shapeless recipe when true and a shaped row recipe when false
- `chance_to_break` is a `0.0..1.0` chance to remove one locator from the held stack, used only when `use_durability` is false
- `use_durability` switches the item to configured durability loss instead of random break chance. In this mode the thrown locator signal always shows the break effect, and the held locator loses durability.
- `durability` controls how many successful uses the locator has when `use_durability` is true
- `stack_size` controls the max stack size when `use_durability` is false and defaults to `64`

When `use_durability` is true, vein locators are not stackable. If this is turned on while players already have stacked locators, the mod splits those stacks safely during inventory updates or when a player uses a stack. If `use_durability` is later turned off, durability data is removed from existing locators during inventory updates or use and they can stack again using `stack_size`.

## Manual Placement

Each vein id is also exposed as a generated structure id:

```mcfunction
/place structure regenerating_ore_veins:diamond_ore
```

This uses the current `veins.json` ids that were available when the datapacks loaded. Manual placement ignores the vein's area whitelist and blacklist so you can test a vein at your current location.

The mod also provides its own placement command:

```mcfunction
/rov place diamond_ore
/regenerating_ore_veins place diamond_ore
```

Use this command when testing config changes. It reads the mod config directly and places the vein at the command source position.

## Updating Existing Veins

After changing `veins.json`, update tracked existing placements without regenerating whole chunks:

```mcfunction
/rov update_existing
/rov update_existing id diamond_ore
/rov update_existing area frontier_0
/rov update_existing id diamond_ore area frontier_0
/rov update_existing area frontier_0 id diamond_ore
/rov update_existing regenerate
/rov update_existing regenerate id diamond_ore
/rov update_existing regenerate area frontier_0
/rov update_existing regenerate id diamond_ore area frontier_0
```

The full command prefix also works:

```mcfunction
/regenerating_ore_veins update_existing id diamond_ore
```

This updates the mod's saved vein entries to the current configuration: interval seconds, jitter, stored vein id, and target block if the old target is no longer part of the configured vein. It does not force-load chunks. For unloaded chunks, saved data is updated immediately and any needed block-state correction happens later when the chunk naturally loads and the mod validates the tracked entry.

The main safety rule is that the command only touches loaded world blocks when the block is still the exact old tracked target or the regenerator block entity is present. This avoids overwriting unrelated player edits. Old saved entries created before vein ids were stored can still be updated when the target block uniquely identifies the vein, or when an explicit `id` is provided and that vein contains the target block.

Adding `regenerate` rebuilds already tracked vein groups using the current `shape`, `min_radius`, `max_radius`, and `fill_factor`. The command reconstructs groups from nearby tracked blocks with the same vein id, removes old tracked positions, creates new tracked positions around the group's center, and queues cleanup/placement work for unloaded chunks. It still avoids force-loading chunks. When those chunks load later, queued cleanup removes old generated ore/regenerator blocks only if they still look like the old tracked blocks, and queued placement writes new ore only into safe replaceable/natural blocks. If a player has built something else there, the queued placement is skipped and that new saved entry is removed instead of overwriting the build.

## Reloading Config

Reload the mod's JSON config without restarting the server:

```mcfunction
/rov reload
/regenerating_ore_veins reload
```

This reloads `global.json`, `areas.json`, `veins.json`, and `vein_locator.json` for runtime generation, `/rov place`, `/rov update_existing`, locator use, locator durability, and locator stack size. Vanilla `/place structure regenerating_ore_veins:<id>` and generated crafting recipes use datapack resources, so adding or removing vein ids or changing recipes may still require Minecraft's `/reload` or a world restart for those resources to refresh.

Note that this reload will not change the crafting recipe immediately, and you will need to restart the world (save and quit then open the world again) for the recipe changes to take effect.

Natural generation does respect `area_whitelist` and `area_blacklist`. The default `frontier_0` example is centered at `x=0, z=0` and is `8192` blocks wide/deep, so it covers roughly `x=-4096..4096` and `z=-4096..4096`.

## Notes

- natural generation is processed once for newly generated chunks
- managed vein blocks are tracked per-dimension in saved data
- when a managed ore block is mined, it is replaced on the next server tick by a `Regenerator Block`

## License

`MIT`
