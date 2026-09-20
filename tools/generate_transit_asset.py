#!/usr/bin/env python3
"""
Generates app/src/main/assets/transit_waterbeach.json from real timetable data.

Source: api.transitous.org (MOTIS) -- free, keyless, aggregates published GB GTFS
(rail + bus). We query real journeys from each candidate origin to Waterbeach
station across the morning arrival window and record the actual services found.

Re-run this after each timetable change (May and December) to refresh the asset.

    python3 tools/generate_transit_asset.py

Journeys are stored per *boarding stop*, pre-composed (bus->rail connections are
resolved here, at authoring time) so the app only has to pick the latest journey
that arrives in time.
"""

import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

API = "https://api.transitous.org/api/v1"
UA = {"User-Agent": "OpenRentCambridge/1.0 (personal commute tool)"}

# Destination of every transit journey. The fixed last mile (Waterbeach -> CRP)
# is applied by the app, not here.
WATERBEACH_STN = (52.2618613, 0.1969942)
CRP = (52.2928224, 0.1702614)  # Cambridge Research Park, OSM way/4408939

# Last-mile minutes as specified by the user. NOTE: the existing Waterbeach
# station is ~3.9 km from CRP, which is nearer 15 min at 15 km/h -- these values
# are kept because they are the user's own figures, and live here so they are a
# one-line change if they refer to the relocated station.
LAST_MILE = {"cycleMin": 7, "shuttleMin": 10}

# A sweep across the morning. Each probe returns several itineraries, so this
# comfortably covers arrivals from ~06:00 to ~10:30.
PROBE_TIMES = ["05:30", "06:00", "06:30", "07:00", "07:15", "07:30", "07:45",
               "08:00", "08:15", "08:30", "08:45", "09:00", "09:30"]

# A weekday with no bank holiday, used purely to read the weekday timetable.
SERVICE_DATE = "2026-09-21"  # Monday

# Candidate origins. Rail stations are explicit boarding points; the
# neighbourhood entries let MOTIS pick whatever real bus/rail stop serves them,
# which is how Cambridge-proper areas get covered without hand-listing stops.
ORIGINS = [
    # id,            display name,        lat,       lon,     openrent search term, slug
    ("ELY",     "Ely",                 52.392227, 0.2667025, "Ely, Cambridgeshire", "ely-cambridgeshire"),
    ("LITTLEPORT", "Littleport",       52.457800, 0.3050000, "Littleport, Cambridgeshire", "littleport-cambridgeshire"),
    ("CBN",     "Cambridge North",     52.226936, 0.1583142, "Chesterton, Cambridge", "chesterton-cambridge"),
    ("CBG",     "Cambridge",           52.194400, 0.1372000, "Cambridge", "cambridge"),
    ("CMB_SOUTH", "Cambridge South",   52.174500, 0.1380000, "Trumpington, Cambridge", "trumpington-cambridge"),
    ("ARBURY",  "Arbury",              52.225000, 0.1250000, "Arbury, Cambridge", "arbury-cambridge"),
    ("KINGS_HEDGES", "Kings Hedges",   52.235000, 0.1400000, "Kings Hedges, Cambridge", "kings-hedges-cambridge"),
    ("MILL_ROAD", "Petersfield / Mill Road", 52.198000, 0.1420000, "Petersfield, Cambridge", "petersfield-cambridge"),
    ("CHERRY_HINTON", "Cherry Hinton", 52.187000, 0.1720000, "Cherry Hinton, Cambridge", "cherry-hinton-cambridge"),
    ("HISTON",  "Histon & Impington",  52.257000, 0.1010000, "Histon, Cambridge", "histon-cambridge"),
    ("COTTENHAM", "Cottenham",         52.286000, 0.1270000, "Cottenham, Cambridge", "cottenham-cambridge"),
]

# Areas close enough to cycle straight to CRP with no transit leg at all.
CYCLE_DIRECT = [
    ("CD_WATERBEACH", "Waterbeach village", 52.265765, 0.1909734, "Waterbeach, Cambridgeshire", "waterbeach-cambridgeshire"),
    ("CD_LANDBEACH",  "Landbeach",          52.280000, 0.1710000, "Landbeach, Cambridgeshire", "landbeach-cambridgeshire"),
    ("CD_MILTON",     "Milton",             52.247000, 0.1560000, "Milton, Cambridge", "milton-cambridge"),
    ("CD_HORNINGSEA", "Horningsea",         52.243000, 0.1930000, "Horningsea, Cambridge", "horningsea-cambridge"),
]

TRANSIT_MODES = {"RAIL", "REGIONAL_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE",
                 "NIGHT_RAIL", "METRO", "SUBWAY", "TRAM", "BUS", "COACH", "FERRY"}


def haversine_m(a, b, c, d):
    R = 6371000.0
    p1, p2 = math.radians(a), math.radians(c)
    dp, dl = math.radians(c - a), math.radians(d - b)
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(x))


def plan(from_ll, to_ll, when_iso, n=4):
    q = urllib.parse.urlencode({
        "fromPlace": f"{from_ll[0]},{from_ll[1]}",
        "toPlace": f"{to_ll[0]},{to_ll[1]}",
        "time": when_iso,
        "arriveBy": "false",
        "numItineraries": str(n),
    })
    req = urllib.request.Request(f"{API}/plan?{q}", headers=UA)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                return json.load(r)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == 2:
                print(f"    ! give up: {e}", file=sys.stderr)
                return {}
            time.sleep(2 * (attempt + 1))
    return {}


def hhmm(iso):
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).strftime("%H:%M")


def extract(itin):
    """Reduce an itinerary to the transit spine: boarding stop, dep, arr, legs."""
    legs = [l for l in itin.get("legs", []) if l.get("mode") in TRANSIT_MODES]
    if not legs:
        return None
    first, last = legs[0], legs[-1]
    # Only keep journeys that genuinely end at Waterbeach.
    if haversine_m(last["to"]["lat"], last["to"]["lon"], *WATERBEACH_STN) > 900:
        return None
    return {
        "boarding": {
            "name": first["from"].get("name", "?"),
            "lat": round(first["from"]["lat"], 6),
            "lon": round(first["from"]["lon"], 6),
        },
        "dep": hhmm(first["startTime"]),
        "arr": hhmm(last["endTime"]),
        "legs": [{
            "mode": "rail" if "RAIL" in l["mode"] else l["mode"].lower(),
            "route": l.get("routeShortName") or l.get("agencyName") or "",
            "from": l["from"].get("name", "?"),
            "to": l["to"].get("name", "?"),
            "dep": hhmm(l["startTime"]),
            "arr": hhmm(l["endTime"]),
        } for l in legs],
    }


def collect(origin_id, name, lat, lon, term, slug):
    print(f"  {name} ...", end="", flush=True)
    by_stop = defaultdict(dict)   # stop key -> {dep: journey}
    for t in PROBE_TIMES:
        res = plan((lat, lon), WATERBEACH_STN, f"{SERVICE_DATE}T{t}:00Z")
        for itin in res.get("itineraries", []):
            j = extract(itin)
            if j:
                key = (j["boarding"]["name"], round(j["boarding"]["lat"], 4),
                       round(j["boarding"]["lon"], 4))
                by_stop[key][j["dep"]] = j
        time.sleep(0.4)

    stops = []
    for (sname, slat, slon), journeys in by_stop.items():
        if len(journeys) < 2:      # too thin to be a usable commute option
            continue
        js = sorted(journeys.values(), key=lambda x: x["dep"])
        stops.append({
            "id": f"{origin_id}__{sname}".replace(" ", "_")[:64],
            "name": sname,
            "areaName": name,
            "mode": js[0]["legs"][0]["mode"],
            "lat": slat, "lon": slon,
            "searchArea": {"term": term, "slug": slug},
            "journeys": [{"dep": j["dep"], "arrWaterbeach": j["arr"], "legs": j["legs"]}
                         for j in js],
        })
    print(f" {len(stops)} stop(s), {sum(len(s['journeys']) for s in stops)} journeys")
    return stops


def main():
    out_path = Path(__file__).resolve().parent.parent / "app/src/main/assets/transit_waterbeach.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"Querying transitous for weekday {SERVICE_DATE} ...")
    stops = []
    for args in ORIGINS:
        stops.extend(collect(*args))

    for sid, name, lat, lon, term, slug in CYCLE_DIRECT:
        stops.append({
            "id": sid, "name": name, "areaName": name, "mode": "cycle_direct",
            "lat": lat, "lon": lon,
            "searchArea": {"term": term, "slug": slug},
            "journeys": [],
        })
        print(f"  {name} ... cycle-direct "
              f"({haversine_m(lat, lon, *CRP)/1000:.1f} km to CRP)")

    # Drop duplicate boarding stops discovered from more than one origin,
    # keeping whichever has the richest timetable.
    best = {}
    for s in stops:
        key = (round(s["lat"], 4), round(s["lon"], 4), s["mode"])
        if key not in best or len(s["journeys"]) > len(best[key]["journeys"]):
            best[key] = s
    stops = sorted(best.values(), key=lambda s: (s["mode"], s["name"]))

    doc = {
        "validFrom": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "serviceDate": SERVICE_DATE,
        "source": "api.transitous.org (MOTIS, published GB GTFS)",
        "destination": {"name": "Cambridge Research Park", "lat": CRP[0], "lon": CRP[1]},
        "waterbeachStation": {"lat": WATERBEACH_STN[0], "lon": WATERBEACH_STN[1]},
        "lastMile": LAST_MILE,
        "stops": stops,
    }
    out_path.write_text(json.dumps(doc, indent=1), encoding="utf-8")
    print(f"\nWrote {out_path}")
    print(f"  {len(stops)} stops, {sum(len(s['journeys']) for s in stops)} journeys, "
          f"{out_path.stat().st_size/1024:.0f} KB")


if __name__ == "__main__":
    main()
