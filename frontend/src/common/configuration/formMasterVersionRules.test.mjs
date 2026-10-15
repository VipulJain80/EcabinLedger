import test from "node:test";
import assert from "node:assert/strict";
import { canPermanentlyDeleteForm, canRestoreAsDraft, emptyDraftPreviewMessage, isCurrentPublishedVersion } from "./formMasterVersionRules.ts";

test("only published history entries can be restored as a draft", () => {
  assert.equal(canRestoreAsDraft("PUBLISHED"), true);
  assert.equal(canRestoreAsDraft("DRAFT"), false);
});

test("an empty draft preview explains that the current published version remains active", () => {
  assert.match(emptyDraftPreviewMessage(1), /empty.*current published version v1/i);
  assert.match(emptyDraftPreviewMessage(null), /empty.*does not affect the current published version/i);
});

test("history identifies the current published pointer and only offers deletion for never-published forms", () => {
  assert.equal(isCurrentPublishedVersion("v1", "v1"), true);
  assert.equal(isCurrentPublishedVersion("v2", "v1"), false);
  assert.equal(canPermanentlyDeleteForm(null, [{ status: "DRAFT" }]), true);
  assert.equal(canPermanentlyDeleteForm("v1", [{ status: "PUBLISHED" }]), false);
  assert.equal(canPermanentlyDeleteForm(null, [{ status: "PUBLISHED" }]), false);
});
