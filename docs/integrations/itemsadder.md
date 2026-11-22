# ItemsAdder integration

This lobby server runs on Minestom (not Paper/Spigot), so it cannot load the ItemsAdder plugin jar directly.
Instead, it reads your ItemsAdder definitions and/or generated resource pack natively:

- **Resource pack delivery** — sends your hosted ItemsAdder resource pack URL to joining players, with optional SHA-1 hash, prompt, and `required` kick enforcement.
- **Custom items** — use `itemsadder:<namespace>:<id>` (or `ia:<namespace>:<id>`) in `hotbar.yml`, `menus.yml` (items and `fill`), `cosmetics.yml` (hats, balloons, outfits, icons), item holograms (`/hologram`), and NPC equipment (`/npc equipment`). Direct `custom-model-data` and `item-model` overrides are also supported.
- **Font images, glyphs, emojis & custom GUI textures** — use `:glyph:`, `:namespace:glyph:`, `:offset_-8:`, `%img_<id>%`, and `%img_offset_<n>%` in menu titles, scoreboard, tab list, holograms, NPC names, boss bar, action bar, messages, and player chat.

Everything in the `itemsadder:` section of `integrations.yml` reloads live with `/setup-lobby reload`.

## 1. Provide ItemsAdder data

Copy one or both of the following from your Paper/Spigot server running ItemsAdder into your lobby server folder:

1. **ItemsAdder folder** (recommended): copy `plugins/ItemsAdder/storage/` (`items_ids_cache.yml`, `font_images_unicode_cache.yml`) and `plugins/ItemsAdder/contents/` into `itemsadder/` inside the lobby folder.
   - If `itemsadder/output/generated.zip` is also present, it is read automatically to pick up custom font keys, space offsets, and 1.21.4+ `item_model` definitions.
2. **Generated resource pack `.zip`**: point `pack-zip` at your `generated.zip` file (works on its own without `contents/` or `storage/`, or combined with `data-path`).

## 2. Configure `integrations.yml`

```yaml
itemsadder:
  enabled: true
  data-path: "itemsadder"
  pack-zip: ""
  resource-pack:
    url: "https://cdn.example.com/itemsadder-pack.zip"
    hash: ""
    required: false
    prompt: "<gold>This server uses a custom resource pack."
    kick-message: "<red>You must accept the server resource pack to play here."
    replace: false
  chat:
    replace-glyphs: true
    glyph-permission: ""
```

- Leave `resource-pack.url` empty (`""`) if your proxy (e.g. Velocity) already sends the resource pack.
- If `resource-pack.hash` is empty and `pack-zip` (or `<data-path>/output/generated.zip`) exists on disk, the SHA-1 hash is computed automatically.

## 3. Using custom items

Reference any ItemsAdder item as `itemsadder:<namespace>:<id>` (or `ia:<namespace>:<id>`):

**`hotbar.yml`**
```yaml
items:
  games:
    slot: 0
    material: "itemsadder:myitems:game_compass"
    name: "<green><bold>Game Selector"
    actions:
      - "open_menu: servers"
```

**`menus.yml`** (with custom GUI background texture and invisible filler)
```yaml
menus:
  servers:
    title: "<white>:offset_-8::myitems:servers_gui:"
    rows: 3
    fill: "itemsadder:_iainternal:icon_empty"
    items:
      bedwars:
        slot: 11
        material: "itemsadder:myitems:bedwars_icon"
        name: "<red><bold>BedWars"
```

**`cosmetics.yml`**
```yaml
hats:
  wizard:
    name: "<light_purple>Wizard Hat"
    material: "itemsadder:myitems:wizard_hat"

outfits:
  knight:
    name: "<gold>Knight Armor"
    helmet: "itemsadder:myitems:knight_helmet"
    chest: "itemsadder:myitems:knight_chestplate"
```

**Holograms & NPCs**
- `/hologram edit <name> item itemsadder:myitems:ruby_sword`
- `/npc equipment <name> set main_hand itemsadder:myitems:ruby_sword`

## 4. Using font images, glyphs & offsets

- **In configs** (`menus.yml`, `display.yml`, `messages.yml`, holograms, NPC names):
  - `:smile:` or `:myitems:smile:`
  - `:offset_-8:`, `:offset_16:`
  - `%img_smile%`, `%img_myitems:smile%`, `%img_offset_-8%`
- **In player chat**:
  - `:smile:` or `:myitems:smile:` (controlled by `itemsadder.chat.replace-glyphs` and `itemsadder.chat.glyph-permission`; defaults to `lobby.chat.emoji`).
