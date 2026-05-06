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

`type` currently supports `box`.

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
    "shape": "circle",
    "min_size": 12,
    "max_size": 24,
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

- `area` what area the vein generation is limited to
- `dimension` when no area is used: accepts a string or list and defaults to `minecraft:overworld`. The vanilla Nether id is `minecraft:the_nether`, though `minecraft:nether` is accepted as an alias.
- `biome` accepts a string or list. Entries can be exact biome ids such as `minecraft:plains` or tags prefixed with `#`, such as `#c:is_jungle`.
- `weights` defaults to `1` for each block
- `shape` supports `circle` and `box`
- `min_size` and `max_size` choose a random shape scale for each vein. They are not a hard cap on placed blocks.
- `fill_factor` is a `0.0..1.0` probability applied to each candidate block in the vein shape. `0` places none of the selected positions, `1` places all selected positions. Defaults to `global.json` value `default_fill_factor`.
- `chunk_minimum_generation_separation` defaults to `8`. Values `>= 1` space candidate chunks apart. Values below `1` multiply generation in each chunk, so `0.5` means two attempts per chunk and `0.25` means four.
- `regeneration_interval_seconds` defaults to `global.json` value `default_regeneration_seconds`
- `regeneration_interval_jitter` defaults to `global.json` value `default_jitter_interval`

If both `area` and `dimension` are set, the area's dimension is used. Biome filtering still applies after the area and dimension match. The default `veins.json` examples are not area-constrained and include Overworld and Nether examples. Vanilla Minecraft does not have separate Overworld quartz ore or netherite ore blocks, so the default examples use `minecraft:quartz_block` for the quartz example and `minecraft:ancient_debris` for netherite.

## Manual Placement

Each vein id is also exposed as a generated structure id:

```mcfunction
/place structure regenerating_ore_veins:diamond_ore
```

This uses the current `veins.json` ids that were available when the datapacks loaded. Manual placement ignores the vein's `area` limit so you can test a vein at your current location.

The mod also provides its own placement command:

```mcfunction
/rov place diamond_ore
/regenerating_ore_veins place diamond_ore
```

Use this command when testing config changes. It reads the mod config directly and places the vein at the command source position.

## Reloading Config

Reload the mod's JSON config without restarting the server:

```mcfunction
/rov reload
/regenerating_ore_veins reload
```

This reloads `global.json`, `areas.json`, and `veins.json` for runtime generation and `/rov place`. Vanilla `/place structure regenerating_ore_veins:<id>` uses generated datapack structure ids, so adding or removing vein ids may still require Minecraft's `/reload` or a world restart for those structure ids to refresh.

Natural generation does respect `area`. The default `frontier_0` example is centered at `x=0, z=0` and is `8192` blocks wide/deep, so it covers roughly `x=-4096..4096` and `z=-4096..4096`.

## Notes

- natural generation is processed once for newly generated chunks
- managed vein blocks are tracked per-dimension in saved data
- when a managed ore block is mined, it is replaced on the next server tick by a `Regenerator Block`

## License

`MIT`
