import test from "node:test";
import assert from "node:assert/strict";
import { normalizeAppBasePath } from "./appBasePath.mjs";

test("accepts a configurable single-segment application prefix", () => {
  assert.equal(normalizeAppBasePath("/ecabin"), "/ecabin");
  assert.equal(normalizeAppBasePath("/fleetcare"), "/fleetcare");
});

test("rejects malformed, trailing, multi-segment, and unsafe prefixes", () => {
  for (const value of ["", "ecabin", "/ecabin/", "/one/two", "/bad?x", "/bad#x", "/bad space", "//host"]) {
    assert.throws(() => normalizeAppBasePath(value), /APP_BASE_PATH/);
  }
});
