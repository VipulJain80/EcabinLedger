import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  aircraftDropdownState,
  aircraftOptionLabel,
  aircraftOptionRequestPath,
  mergeAircraftPages,
  moveActiveAircraftIndex,
  nextAircraftPage,
} from "./aircraftDropdownModel.mjs";

const option = (id, tailNumber = `N-${id}`) => ({ id, tailNumber, msnNumber: null, aircraftType: "A320" });

test("aircraft dropdown requests the existing API one 20-item page at a time", () => {
  assert.equal(aircraftOptionRequestPath("N-10", 2), "/aircraft/options?search=N-10&page=2&size=20");
  assert.equal(nextAircraftPage({ page: 0, totalPages: 2, items: [], size: 20, totalElements: 21 }), 1);
  assert.equal(nextAircraftPage({ page: 1, totalPages: 2, items: [], size: 20, totalElements: 21 }), undefined);
});

test("pages append unique aircraft without losing the separately retained selected value", () => {
  const selected = option("selected", "VH-SELECTED");
  const loaded = mergeAircraftPages([
    { items: [option("1"), option("2")], page: 0, totalPages: 2, size: 20, totalElements: 21 },
    { items: [option("2"), option("3")], page: 1, totalPages: 2, size: 20, totalElements: 21 },
  ]);

  assert.deepEqual(loaded.map(item => item.id), ["1", "2", "3"]);
  assert.equal(aircraftOptionLabel(selected), "VH-SELECTED — MSN —");
  assert.equal(selected.id, "selected");
});

test("loading, error, empty, and ready states are distinguishable", () => {
  assert.equal(aircraftDropdownState({ pending: true, error: false, options: [] }), "loading");
  assert.equal(aircraftDropdownState({ pending: false, error: true, options: [] }), "error");
  assert.equal(aircraftDropdownState({ pending: false, error: false, options: [] }), "empty");
  assert.equal(aircraftDropdownState({ pending: false, error: false, options: [option("1")] }), "ready");
});

test("keyboard movement wraps and supports first and last options", () => {
  assert.equal(moveActiveAircraftIndex(-1, "next", 3), 0);
  assert.equal(moveActiveAircraftIndex(0, "previous", 3), 2);
  assert.equal(moveActiveAircraftIndex(1, "first", 3), 0);
  assert.equal(moveActiveAircraftIndex(1, "last", 3), 2);
  assert.equal(moveActiveAircraftIndex(0, "next", 0), -1);
});

test("selector keeps search in the listbox panel and ignores stale queries by key and abort signal", async () => {
  const source = await readFile(new URL("./AircraftRegistrationSelect.tsx", import.meta.url), "utf8");
  assert.match(source, /queryKey: \["reporting-aircraft-options", cacheKey, areaId, debouncedSearch\]/);
  assert.match(source, /queryFn: async \(\{ pageParam, signal \}\)/);
  assert.match(source, /\{ signal \}/);
  assert.match(source, /setTimeout\(\(\) => setDebouncedSearch\(search\.trim\(\)\), 250\)/);
  assert.match(source, /role="combobox"/);
  assert.match(source, /onClick=\{event => \{ event\.preventDefault\(\); setOpen\(true\); \}\}/);
  assert.match(source, /h-\[42px\].*self-start/);
  assert.match(source, /search\.trim\(\) === debouncedSearch/);
  assert.match(source, /<div className="border-b border-\[#e5eaf0\] p-2">[\s\S]*<input/);
  assert.match(source, /role="listbox"/);
  assert.match(source, /onScroll=\{loadMoreIfNearEnd\}/);
  assert.match(source, /aria-activedescendant/);
  assert.match(source, /event\.key === "Escape"/);
  assert.match(source, /event\.key === "Enter" && activeOption/);
});

test("form binding and selected aircraft callback remain the form submission contract", async () => {
  const renderer = await readFile(new URL("./OperationalFormRenderer.tsx", import.meta.url), "utf8");
  const selector = await readFile(new URL("./AircraftRegistrationSelect.tsx", import.meta.url), "utf8");
  assert.match(renderer, /onAircraftSelected\?\.\(option\)/);
  assert.match(renderer, /formId="defect-report-form"/);
  assert.match(selector, /form=\{formId\}/);
  assert.match(selector, /required=\{required\}/);
  assert.match(selector, /value=\{selected\?\.id \|\| ""\}/);
  assert.match(selector, /selected\?\.id === option\.id/);
});
