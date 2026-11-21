---
okf_version: "0.2"
type: Design Note
title: "ParamAuto search design — quota conservation"
description: "Why search burned the 500-session Mapbox limit, and the button-only, cache-first design with Home/Office and most-visited places that replaced it."
tags: [paramauto, search, mapbox, caching]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# Search design — quota conservation

## Problem (Param, 2026-10-27)
The 500-search limit was being hit very fast: SearchScreen auto-searched on every text
change (a debounced fetch ~300 ms after typing stopped), so ordinary typing burned
Mapbox SearchBox sessions continuously.

## Design (shipped in commit a4119ea4c)
- **No auto-search.** Typing makes zero network calls. Network search runs **only** when the user taps the Search button (or the keyboard search action).
- **Cache-first.** `SearchCache` (SharedPreferences `search_cache`): key = normalized query (trim/lowercase/collapse spaces); value = results + timestamp. A hit renders instantly — no spinner, no network. A miss runs the existing Mapbox → Photon flow once and writes results back. Bounds: max 200 queries (oldest evicted); entries older than 90 days count as misses.
- **Typing filters the cache locally** — matches from already-cached places appear while typing, still with no network.
- **Search home shows places, not a blank box**: Home and Work/Office shortcuts first, then **★ Most visited** (top 8). `DestinationHistory` (SharedPreferences `destination_history`) records every destination the user actually starts navigating to (name, address, coords, visitCount, lastUsed), deduped by destination, capped at 50. Tapping a shortcut/history entry starts navigation with no search call.
- Both stores survive restarts; no new dependencies.

## Residual quota costs
- A cache-hit Mapbox suggestion without coordinates still costs **one retrieve** when tapped (user-initiated selection, not a search session). Photon results and history entries carry coordinates and skip it.

## Related
- [build-history.md](build-history.md) · [architecture.md](architecture.md)
