# OpenRent Cambridge

Finds rental properties on OpenRent that you can actually commute from, to
**Cambridge Research Park (CRP)**, by a time you choose.

The commute is the point. Rather than searching a fixed list of areas, the app
works out which places real public transport puts within your limit, searches
only those, and then costs every property's door-to-door journey using real
timetable data.

## How it works

You set an arrival time, a maximum acceptable commute, and whether the last mile
from Waterbeach to CRP is by bike or shuttle. From that:

```
leave home --cycle--> boarding stop --[timetabled journey]--> Waterbeach --last mile--> CRP
```

* The **max commute** decides which stops are reachable, and therefore which
  OpenRent areas get searched at all. Change the slider and the area list
  visibly changes.
* The **arrival time** drives a real timetable lookup: the latest service that
  still gets you in on time.
* Each result card shows the total travel time, when to leave, the individual
  train and bus legs, and the last mile.

`totalMin` is time spent *travelling*. If an infrequent train drops you at work
25 minutes early, that is reported as slack on the card rather than inflating the
commute — otherwise good-but-sparse rail links score worse than they deserve.

Areas close enough to cycle straight to CRP (Landbeach, Waterbeach village,
Milton, Horningsea) skip the transit leg entirely, and often win.

## Structure

```
model/Models.kt            domain types; times are minutes since midnight
data/CommuteCalculator.kt  pure commute arithmetic, no Android imports
data/TransitRepository.kt  loads and merges the bundled timetable
data/OpenRentClient.kt     search scraping + JSON hydration
data/SearchRepository.kt   area selection -> search -> filter -> cost
MainViewModel.kt           screen state
ui/                        setup, search, results, card, WebView detail
```

## Scraping notes

OpenRent renders only the first 20 result cards into the HTML, but it also emits
parallel JavaScript arrays describing **every** match on the page, including
exact coordinates:

```
var PROPERTYIDS = [...]; var PROPERTYLISTLATITUDES = [...]; var prices = [...];
```

Reading those arrays means one request per area yields every property already
located — no need to open each listing to find out where it is. Titles and
descriptions are not in the arrays, so surviving listings are hydrated afterwards
from `/search/propertiesbyid` (JSON, **max 20 ids per call**).

Three things worth remembering:

* **The URL filters are unreliable.** A `prices_min=800&bedrooms_max=2` search
  still returns sub-£800 rooms and 5-bed houses, so everything is filtered again
  locally against the real values.
* **`islivelistBool` is the let-agreed flag.** `0` means let agreed, `1` means
  still available. This was checked against `letAgreed` from
  `/search/propertiesbyid` over 120 listings in three areas and agreed every
  time. Reading it from the search page means let-agreed properties are dropped
  from the *full* result set before any hydrate request is spent on them.
* **Listing URLs need only the id.** `/property-to-rent/p/{id}/{id}` 301-redirects
  to the canonical page.

### Filters

All filtering happens locally in `SearchRepository.matches`, against the real
values from the page arrays. Beyond price and bedrooms:

| Filter | Array | Notes |
|---|---|---|
| Exclude shared | `isshared` | on by default |
| Exclude student-only | `students` / `nonStudents` | student-only means students yes, others no |
| Furnishing | `furnished` / `unfurnished` | independent flags; both set means "furnishing at tenant choice" and matches either |
| Bills included | `bills` | |
| Move in before | `availableFrom` | days from today, negative meaning already available |
| Longest tenancy | `minimumTenancy` | months; `0` means the landlord stated no minimum |

Two things to know:

* **`availableFrom`'s reference date is a day fuzzy.** A listing advertising
  23 November 2026 carried the value 64 on a page fetched on 19 September, so the
  reference is effectively tomorrow. The move-in filter therefore allows a day of
  slack — showing a borderline property beats hiding one that would have worked.
* **DSS/LHA listings are always excluded**, like let-agreed ones, via
  `rentCoveredDssOrPreferred`. In practice this currently removes nothing: across
  four areas, no available property in the £800-2000 1-2 bed range carried the
  flag. The field parses correctly (the Ely fixture has six, five already let
  agreed) — the stock simply is not there.

**Let-agreed properties are always excluded.** This is not optional, because they
dominate: a typical run found 229 of 407 results (56%) already let agreed, and in
Ely only 2 of 42 properties were still available. Leaving them in would bury the
handful you can actually rent. Both the search and results screens say so, so a
small result count is never a mystery.

This format is undocumented. `OpenRentParserTest` pins it against a saved page in
`app/src/test/resources/`, so a redesign fails the build rather than silently
returning nothing.

## Refreshing the timetable

`app/src/main/assets/transit_waterbeach.json` is generated, not hand-written:

```bash
python3 tools/generate_transit_asset.py
```

It queries [transitous](https://api.transitous.org) (MOTIS over published GB
GTFS; free, no API key) for real journeys from each candidate area to Waterbeach
across the morning, and pre-composes any bus→rail connections so the app only has
to pick the latest journey arriving in time. Takes about 15 minutes.

Rail timetables change each **May and December** — re-run it then. The app shows
the asset's `validFrom` date on the setup screen, and warns if you ask for an
arrival time the data does not cover (it holds the morning commute only).

To change which areas are considered, edit `ORIGINS` and `CYCLE_DIRECT` in the
script and regenerate.

## Sorting

Six orders, chosen from the results screen. Each one explains itself under the
control, because several are not guessable from the label.

| Sort | Orders by |
|---|---|
| Commute | shortest door-to-door travel time |
| **Lie-in** | **latest you can leave home and still arrive on time** |
| Price | cheapest per calendar month |
| £ per bed | cheapest per bedroom, comparing 1- and 2-beds fairly |
| Newest | most recently listed, from `dateFirstListedMs` |
| Available | soonest move-in date |

**Lie-in is the one worth knowing about.** Within a single boarding stop it is
just the mirror of Commute, but across stops it is not, because the last usable
service differs wildly: the spread of "what time does the last qualifying service
put me at work" is **145 minutes**. A property served only by a 06:22 train has a
genuinely short *journey* while demanding a 05:50 alarm, and Commute ranks it
well while Lie-in correctly drops it. Cards also flag this directly, with
"gets you in N min early" whenever the slack exceeds ten minutes.

`£ per bed` treats a studio as one bedroom so it compares sensibly against 1-beds.

## Caveats

* **The last mile is taken on trust.** `lastMile` in the asset is set to 7 min by
  bike and 10 by shuttle, as specified. Measured against the *existing* Waterbeach
  station, CRP is 3.9 km away, which is nearer 15 min at 15 km/h. If those figures
  refer to the relocated station, they are right; otherwise edit `lastMile` in the
  asset (or `LAST_MILE` in the generator) and every result updates.
* **Locations are approximate.** OpenRent shows approximate positions until you
  enquire, so treat commute figures as give-or-take a few minutes.
* Cycling is estimated at 15 km/h with a 1.3x road-detour factor on the
  straight-line distance — see the constants in `CommuteCalculator`.

## Build and test

```bash
./gradlew testDebugUnitTest   # 61 tests: commute maths, filters, sorting, parser fixtures, asset validation
./gradlew installDebug        # to a connected device
```
