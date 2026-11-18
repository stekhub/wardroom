# Group Icons

Every newsgroup gets a round icon: a white symbol on a colored circle. It is derived in the frontend from the group's name and description, so it needs no backend, API or data model support. The same input always gives the same icon.

| Place | Size |
|---|---|
| Sidebar entry | 20 px |
| Discover row | 20 px |
| Group header | 64 px |

The icon is decorative (`aria-hidden`); the group name next to it carries the meaning.

## Symbol

Symbols are [Lucide](https://lucide.dev) icons, the icon set of shadcn/ui ([ADR 0007](../architecture/decisions/0007-frontend-stack.md)). Only the icons in the keyword table are bundled.

The name is lowercased and split at dots into segments. A leading country hierarchy is dropped: a first segment of two letters (`de`, `fr`, `uk`, …) or one of `aus`, `can`, `japan`. Of the remaining segments, the first is the top-level hierarchy, the rest are the topic segments.

The symbol is the first match in this order:

1. **Topic segments**, from the last to the first. A segment matches if it equals a keyword, or if one of its parts split at `-` or `_` equals a keyword. Segments in the ignore list are skipped.
2. **Description words**, in order. Words are runs of letters, digits, `+` and `#`, lowercased. Only keywords with at least three characters are matched here.
3. **Top-level hierarchy**, from the hierarchy table.
4. **Generic icon**: `messages-square`.

Ignored segments: `misc`, `general`, `d`, `discuss`, `discussion`, `announce`, `moderated`, `info`, `test`.

Examples:

| Group | Match | Icon |
|---|---|---|
| `comp.lang.java.programmer` | `java` (segment) | `coffee` |
| `de.comp.lang.java` | `java` (segment, `de` dropped) | `coffee` |
| `sci.space.history` | `history` (last segment first) | `scroll` |
| `rec.food.cooking` | `cooking` | `chef-hat` |
| `alt.example.misc` described as "Talk about guitars" | no segment match; `guitars` (description) | `guitar` |
| `comp.unknown` | no segment or description match; `comp` (hierarchy) | `cpu` |
| `foo.bar` | nothing | `messages-square` |

## Color

- Input: the top-level hierarchy and the first topic segment, joined with a dot (for example `comp.lang`), or only the top-level hierarchy if there is no topic segment. Related groups therefore share a color.
- Hash: 32-bit FNV-1a over the UTF-8 bytes. Color index: hash modulo 12.
- Palette, Tailwind CSS 600 shades in this order: red, orange, amber, green, teal, cyan, sky, blue, indigo, violet, fuchsia, rose. Each must reach a contrast of at least 3:1 against the white symbol.

## Hierarchy table

| Hierarchy | Icon |
|---|---|
| `comp` | `cpu` |
| `sci` | `atom` |
| `rec` | `party-popper` |
| `soc` | `users` |
| `talk` | `message-circle` |
| `news` | `newspaper` |
| `misc` | `shapes` |
| `humanities` | `library` |
| `alt` | `sparkles` |
| `biz` | `briefcase` |
| `gnu` | `terminal` |
| `k12` | `school` |
| `bionet` | `dna` |

## Keyword table

The initial table: about 400 keywords for about 150 symbols. It is extended through contributions; keywords are lowercase and unique across the table.

**Computing**

| Icon | Keywords |
|---|---|
| `code-xml` | `lang`, `programming`, `programmer`, `software`, `c`, `c++`, `c#`, `fortran`, `ada`, `pascal`, `basic`, `smalltalk`, `haskell`, `php`, `cobol` |
| `coffee` | `java`, `coffee`, `tea` |
| `braces` | `javascript`, `json` |
| `code` | `python`, `perl`, `tcl`, `html`, `xml`, `css` |
| `gem` | `ruby`, `gems`, `jewelry` |
| `parentheses` | `lisp`, `scheme`, `clojure` |
| `cog` | `rust`, `compilers`, `mechanical` |
| `terminal` | `unix`, `linux`, `bsd`, `freebsd`, `shell`, `hackers` |
| `square-terminal` | `editors`, `emacs`, `vi`, `vim` |
| `app-window` | `os`, `windows`, `ms-windows` |
| `laptop` | `mac`, `macintosh`, `laptops` |
| `monitor` | `pc`, `ibm`, `computers`, `computer` |
| `joystick` | `amiga`, `atari`, `cbm`, `commodore`, `retro`, `retrocomputing` |
| `circuit-board` | `hardware`, `arch`, `electronics`, `embedded` |
| `microchip` | `cpu`, `chips`, `semiconductors` |
| `hard-drive` | `storage`, `periphs` |
| `server` | `admin`, `sysadmin`, `servers` |
| `database` | `databases`, `database`, `sql` |
| `network` | `networks`, `networking`, `protocols`, `dcom`, `tcp-ip` |
| `globe` | `internet`, `www`, `web`, `infosystems` |
| `mail` | `mail`, `email` |
| `shield` | `security`, `military`, `defense` |
| `lock` | `crypt`, `cryptography`, `privacy`, `encryption` |
| `bot` | `ai`, `robotics`, `robots` |
| `image` | `graphics`, `multimedia` |
| `sigma` | `tex`, `latex`, `math`, `mathematics` |
| `git-branch` | `vcs`, `git` |
| `container` | `virtualization`, `cloud` |
| `bug` | `bugs`, `testing`, `debugging` |
| `ruler` | `cad`, `design` |
| `printer` | `printers`, `printing` |

**Science**

| Icon | Keywords |
|---|---|
| `atom` | `physics`, `nuclear` |
| `flask-conical` | `chem`, `chemistry` |
| `calculator` | `stat`, `statistics`, `numerical` |
| `telescope` | `astro`, `astronomy` |
| `rocket` | `space`, `sf`, `rockets` |
| `orbit` | `ufo`, `ufos`, `seti` |
| `dna` | `bio`, `biology`, `genetics` |
| `microscope` | `research`, `lab` |
| `stethoscope` | `med`, `medicine`, `medical`, `doctors` |
| `heart-pulse` | `health`, `diet` |
| `pill` | `pharmacy`, `drugs` |
| `brain` | `psychology`, `philosophy`, `neuroscience` |
| `chart-line` | `econ`, `economics`, `invest`, `investing`, `stocks` |
| `mountain` | `geo`, `geology`, `volcanoes` |
| `earth` | `environment`, `ecology`, `climate` |
| `zap` | `energy`, `power`, `electrical` |
| `hard-hat` | `engr`, `engineering`, `civil` |
| `languages` | `linguistics`, `languages`, `language` |
| `scroll` | `history`, `folklore`, `archaeology` |
| `cloud-sun` | `weather`, `meteorology` |

**Arts and media**

| Icon | Keywords |
|---|---|
| `music` | `music`, `songs` |
| `guitar` | `guitar`, `guitars` |
| `piano` | `piano`, `keyboards` |
| `drum` | `drums`, `percussion` |
| `mic-vocal` | `singing`, `opera`, `choir` |
| `headphones` | `audio`, `hifi` |
| `disc-3` | `records`, `vinyl`, `cd` |
| `radio` | `radio`, `ham`, `amateur-radio` |
| `film` | `movies`, `film`, `cinema` |
| `tv` | `tv`, `television` |
| `camera` | `photo`, `photography`, `cameras` |
| `palette` | `arts`, `art`, `painting` |
| `drama` | `theatre`, `theater`, `drama` |
| `feather` | `poetry`, `writing`, `writers` |
| `book-open` | `books`, `literature`, `reading` |
| `sparkles` | `anime`, `manga`, `fantasy` |
| `book-image` | `comics` |
| `laugh` | `humor`, `humour`, `jokes`, `comedy` |
| `skull` | `horror` |
| `ghost` | `paranormal`, `occult` |
| `star` | `fan`, `fandom`, `celebrities` |
| `wand-sparkles` | `magic` |
| `footprints` | `dance`, `dancing` |
| `newspaper` | `journalism`, `media`, `usenet` |

**Games and hobbies**

| Icon | Keywords |
|---|---|
| `gamepad-2` | `games`, `gaming`, `video-games` |
| `dices` | `rpg`, `boardgames`, `dice` |
| `spade` | `cards`, `bridge`, `poker` |
| `crown` | `chess` |
| `puzzle` | `puzzles`, `models`, `modeling` |
| `scissors` | `crafts`, `sewing`, `knitting`, `quilting` |
| `hammer` | `woodworking`, `diy`, `tools` |
| `coins` | `coins`, `numismatics`, `collecting` |
| `mail-open` | `stamps`, `philately` |
| `clock` | `antiques`, `watches`, `clocks` |
| `flower` | `gardens`, `gardening`, `flowers` |
| `tent` | `outdoors`, `camping`, `scouting` |
| `backpack` | `hiking`, `backpacking` |
| `mountain-snow` | `climbing`, `skiing`, `snowboarding` |

**Sports**

| Icon | Keywords |
|---|---|
| `trophy` | `sport`, `sports` |
| `goal` | `soccer`, `football` |
| `volleyball` | `volleyball`, `baseball`, `basketball`, `hockey`, `tennis`, `golf`, `cricket`, `rugby` |
| `bike` | `bicycles`, `cycling`, `bikes`, `motorcycles` |
| `dumbbell` | `fitness`, `bodybuilding`, `running` |
| `waves` | `swimming`, `scuba`, `diving`, `surfing` |
| `sailboat` | `sailing`, `boats`, `boating` |
| `fish` | `fishing`, `aquaria`, `aquarium`, `fish` |
| `target` | `guns`, `archery`, `shooting`, `hunting` |
| `medal` | `olympics`, `athletics` |

**Food and drink**

| Icon | Keywords |
|---|---|
| `chef-hat` | `cooking`, `recipes`, `cuisine` |
| `utensils` | `food`, `restaurants` |
| `beer` | `beer`, `brewing`, `homebrew` |
| `wine` | `wine`, `wines` |
| `martini` | `drink`, `drinks`, `cocktails` |
| `vegan` | `vegetarian`, `vegan` |
| `pizza` | `pizza`, `fastfood` |
| `cake-slice` | `baking`, `desserts` |

**Animals and nature**

| Icon | Keywords |
|---|---|
| `paw-print` | `pets`, `animals` |
| `dog` | `dogs` |
| `cat` | `cats` |
| `bird` | `birds`, `birding` |
| `rabbit` | `rabbits` |
| `turtle` | `reptiles` |
| `trees` | `forestry`, `trees`, `genealogy` |
| `sprout` | `agriculture`, `farming`, `plants` |

**Travel and transport**

| Icon | Keywords |
|---|---|
| `plane` | `aviation`, `aircraft`, `flying` |
| `car` | `autos`, `cars`, `automotive` |
| `train-front` | `railroad`, `railways`, `trains` |
| `bus` | `transport`, `transit` |
| `ship` | `ships`, `maritime` |
| `luggage` | `travel`, `tourism` |
| `map` | `maps`, `geography` |

**Society**

| Icon | Keywords |
|---|---|
| `landmark` | `politics`, `government` |
| `vote` | `elections` |
| `scale` | `law`, `legal`, `rights` |
| `church` | `religion`, `christian`, `christianity` |
| `book` | `bible` |
| `users` | `culture`, `community` |
| `venus` | `women` |
| `mars` | `men` |
| `rainbow` | `motss`, `lgbt` |
| `heart` | `couples`, `singles`, `romance` |
| `baby` | `parenting`, `parents`, `kids` |
| `graduation-cap` | `college`, `education`, `university` |
| `school` | `schools`, `teaching` |
| `accessibility` | `disability`, `disabled` |
| `hand-heart` | `support`, `volunteering` |
| `swords` | `war`, `wars`, `wwii` |

**Business and money**

| Icon | Keywords |
|---|---|
| `briefcase` | `jobs`, `business`, `careers` |
| `banknote` | `finance`, `money`, `banking` |
| `bitcoin` | `bitcoin`, `crypto`, `cryptocurrency` |
| `receipt` | `taxes`, `tax` |
| `shopping-cart` | `forsale`, `market`, `marketplace` |
| `store` | `consumers`, `shopping` |
| `megaphone` | `marketing`, `advertising` |
| `house` | `house`, `home`, `housing` |

**Help and meta**

| Icon | Keywords |
|---|---|
| `circle-help` | `help`, `newusers`, `answers`, `faq` |
| `archive` | `binaries` |
