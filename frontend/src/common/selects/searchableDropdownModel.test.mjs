import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { filterDropdownOptions, nextSelectedValues, shouldUseSearchableSelect } from "./searchableDropdownModel.mjs";

test("only lists large enough to benefit from search use the local searchable control", () => {
  assert.equal(shouldUseSearchableSelect(9), false);
  assert.equal(shouldUseSearchableSelect(10), true);
  assert.equal(shouldUseSearchableSelect(500, 500), false);
  assert.equal(shouldUseSearchableSelect(499, 500), true);
});

test("local search matches labels and descriptions without changing option values", () => {
  const options = [{ value: "a", label: "Cabin Quality", description: "Maintenance team" }, { value: "b", label: "Galley" }];
  assert.deepEqual(filterDropdownOptions(options, "maintenance"), [options[0]]);
  assert.deepEqual(filterDropdownOptions(options, " GALLEY "), [options[1]]);
  assert.deepEqual(filterDropdownOptions(options, ""), options);
});

test("multi-select toggles preserve order and unrelated selected values", () => {
  assert.deepEqual(nextSelectedValues(["a", "b"], "a"), ["b"]);
  assert.deepEqual(nextSelectedValues(["a"], "c"), ["a", "c"]);
});

test("searchable controls keep inline search, scrollable options, and keyboard dismissal/selection", async () => {
  const source = await readFile(new URL("./SearchableSelect.tsx", import.meta.url), "utf8");
  const renderer = await readFile(new URL("../forms/OperationalFormRenderer.tsx", import.meta.url), "utf8");
  const aircraft = await readFile(new URL("../forms/AircraftRegistrationSelect.tsx", import.meta.url), "utf8");
  assert.match(source, /type="search"/);
  assert.match(source, /flex max-h-64 flex-col/);
  assert.match(source, /max-h-40 flex-1 list-none overflow-y-auto/);
  assert.match(source, /h-\[42px\] min-h-\[42px\]/);
  assert.match(renderer, /field\.type === "MULTI_SELECT" \|\| \(field\.type === "DROPDOWN"/);
  assert.match(aircraft, /flex max-h-64 flex-col/);
  assert.match(aircraft, /max-h-40 flex-1 list-none overflow-y-auto/);
  assert.match(source, /role="listbox"/);
  assert.match(source, /event\.key === "Escape"/);
  assert.match(source, /event\.key === "Enter" && activeOption/);
  assert.match(source, /onInvalid=/);
  assert.match(source, /aria-activedescendant/);
  assert.match(source, /aria-checked|type="checkbox"/);
});
