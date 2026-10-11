import assert from "node:assert/strict";
import test from "node:test";
import { areaFormSelection, selectableCabinAreas, shouldShowCabinAreaChooser, singleAreaForm } from "./areaFormSelection.ts";

test("an area with no active published forms has an empty state", () => {
  assert.equal(areaFormSelection([]), "none");
});

test("one applicable form proceeds directly with its exact published version", () => {
  const selected = { id: "form-1", name: "Passenger Cabin", versionId: "version-7" };
  assert.equal(areaFormSelection([selected]), "single");
  assert.deepEqual(singleAreaForm([selected]), selected);
  assert.equal(singleAreaForm([selected])?.versionId, "version-7");
});

test("multiple applicable forms require an explicit selection", () => {
  const forms = [
    { id: "form-1", versionId: "version-2" },
    { id: "form-2", versionId: "version-5" },
  ];
  assert.equal(areaFormSelection(forms), "multiple");
  assert.equal(singleAreaForm(forms), null);
});

test("cabin area chooser is visible as soon as an authorized aircraft is loaded", () => {
  assert.equal(shouldShowCabinAreaChooser(true, true), true);
  assert.equal(shouldShowCabinAreaChooser(false, true), false);
  assert.equal(shouldShowCabinAreaChooser(true, false), false);
});

test("area choices come only from records with server-provided ids", () => {
  const areas = [{ id: "area-cabin", name: "Cabin" }, { name: "Unpersisted" }];
  assert.deepEqual(selectableCabinAreas(areas), [{ id: "area-cabin", name: "Cabin" }]);
});
