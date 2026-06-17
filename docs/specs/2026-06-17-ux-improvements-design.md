# BookChatAndroid UX Improvements — Design Spec

Date: 2026-06-17
Target: `binhex/BookChatAndroid` (fork of `bernpuc/BookChatAndroid`)

## 1. Password Masking in Diagnostics

### Problem
The IRC password is sent as `PRIVMSG NickServ :IDENTIFY <password>` and gets emitted to
`sentLines`, which feeds both the in-app diagnostics panel and the exported IRC log file.
The password is stored in plaintext in the log.

### Approach
Filter the password out at the earliest point where the line flows into diagnostics —
in `IrcRepository`, before the line reaches `sentLines` AND before lines are collected
into the diagnostics panel. The actual IRC command is unaffected.

### Changes
- `IrcRepository.kt` — add a `maskPasswordIfNeeded(line: String)` utility that replaces
  `IDENTIFY <password>` with `IDENTIFY ••••` before emitting to `sentLines`.
- `SearchViewModel.kt` — the inbound-lines listener already captures `sentLines`;
  the masking is already applied by then, so no additional change needed here.
- `DiagnosticsLogger` — the rotation/pruning logic is unchanged; it receives already-masked lines.

---

## 2. Download Queue Persistence

### Problem
The download queue is entirely in-memory. If the app is killed or the device restarts,
all queued downloads are lost.

### Approach
Serialize the queue to a DataStore Preferences entry as JSON. On `DownloadRepository` init,
load persisted items and process any that have not yet been sent (state `Queued`).
Items that were in `RequestSent` or later state at the time of crash are treated as lost
(no response from IRC on reconnect) and surfaced as `Failed`.

### Changes
- **New file:** `DownloadQueueStore.kt` in `com.bookchat.service`
  - Methods: `saveQueue(items)`, `loadQueue(): List<DownloadItem>`
  - JSON serialization via `org.json` (matching `BotStatsRepository` pattern)
  - Max 20 items to keep writes small
- `DownloadRepository.kt` — inject `DownloadQueueStore`, call `loadQueue()` in `init`,
  call `saveQueue()` after every mutation (`enqueue`, `removeFromQueue`, `processNext`,
  `moveToCompleted`)

---

## 3. Sort & Filter Search Results

### Problem
Search results are displayed in rank order (by bot reliability) with no user control.
Users cannot sort by file size, format, or filter by language.

### Approach
Add a row of two dropdown `FilterChip` components above the results list:
- **Sort by:** Reliability (default), Size (largest first), Format (EPUB first)
- **Filter by language:** All, English only
Selection is local UI state; results are derived via `sortedAndFiltered` on the existing list.

### Changes
- `SearchScreen.kt` — add `SortFilterBar` composable above `ResultsList`
  - Two `FilterChip` + `DropdownMenu` combos
  - Options: sort keys + filter keys as sealed classes
- `SearchViewModel.kt` — add `sortMode: StateFlow<SortMode>` and
  `languageFilter: StateFlow<LanguageFilter>` with default values
- `SearchResult` — no changes; sorting/filtering is done on the existing fields
  (`fileSizeBytes`, `format`, `language`, `reliabilityScore`)

### Sort modes
| Mode | Comparator |
|---|---|
| Reliability | `reliabilityScore` descending (nulls last) |
| Size | `fileSizeBytes` descending |
| Format priority | EPUB > MOBI > AZW3 > PDF > other (by `formatScore`) |

---

## 4. Search Timeout Retry & Pull-to-Refresh

### Problem
When a search times out (30s), the user sees an error state but must re-type the query
to retry. There is no way to refresh the results list.

### Approach
- The existing `Error` state in `SearchUiState` gains a **Retry** button that resends
  the last query.
- The results `LazyColumn` gets a Material 3 `pullToRefresh` modifier that repeats the
  last search on pull-down.

### Changes
- `SearchViewModel.kt` — store `lastQuery: String` (populated on every `onSearch()`)
- `SearchScreen.kt`:
  - `NoResultsState` → add retry button that calls `viewModel.onSearch()` (re-reads `searchText`)
  - Wrap `ResultsList` in `PullToRefreshBox` from Material 3
- `SearchRepository.kt` — expose `lastQuery` or let ViewModel cache it

---

## 5. Multi-Select Batch Downloads

### Problem
Each search result must be downloaded individually, one at a time. No batch capability.

### Approach
A "Select" toggle button in the search bar area activates checkbox mode.
When active, each result card shows a checkbox. A bottom bar appears with
"Download (N)" that enqueues all selected items.

### Changes
- `SearchViewModel.kt`:
  - `selectionMode: StateFlow<Boolean>`
  - `selectedHashes: StateFlow<Set<String>>`
  - `toggleSelection(hash: String)`, `clearSelection()`, `selectAll()`, `batchDownload()`
- `SearchScreen.kt`:
  - Toggle button (icon: `CheckBox` / `CheckBoxOutlineBlank`) in the search bar row
  - `BookResultCard` checks `selectionMode` and shows a checkbox on the leading edge
  - Bottom floating bar: `Surface` with `"Download (N)"` button, appears only when
    `selectionMode && selectedHashes.isNotEmpty()`
- Batch download enqueues via `searchRepository.enqueue()` for each selected result

---

## 6. Clear Recent Searches

### Problem
Recent searches accumulate indefinitely with no way to clear them.

### Approach
Add a "Clear history" item at the bottom of the recent-searches `DropdownMenu`.
Calls a new `clearRecentSearches()` on `SettingsRepository`.

### Changes
- `SettingsRepository.kt` — add `suspend fun clearRecentSearches()`
- `SearchScreen.kt` — add a `DropdownMenuItem("Clear history")` at the end of the
  recent searches dropdown

---

## Files Changed Summary

| File | Change |
|---|---|
| `IrcRepository.kt` | Password masking in `sentLines` |
| `DownloadQueueStore.kt` | **New** — JSON persistence for queue |
| `DownloadRepository.kt` | Inject & use `DownloadQueueStore` |
| `SearchViewModel.kt` | Sort/filter state, selection state, lastQuery cache |
| `SearchScreen.kt` | Sort/filter chips, batch select UI, retry, pull-to-refresh, clear recents |
| `BookResultCard` (in `SearchScreen.kt`) | Selection checkbox |
| `SettingsRepository.kt` | `clearRecentSearches()` method |
| `SearchRepository.kt` | Expose `lastQuery` |
