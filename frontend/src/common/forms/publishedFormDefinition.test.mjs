import assert from "node:assert/strict";
import test from "node:test";
import { orderedOperationalFields } from "./publishedFormDefinition.ts";
import { MAX_IMAGE_BYTES, maxBytesForFile, partitionEvidenceFiles, validateEvidenceFile } from "./evidenceFileRules.ts";

test("configured evidence fields render last while other configured order stays unchanged", () => {
  const fields = [
    { key: "evidence", order: 0, binding: "EVIDENCE_ATTACHMENTS" },
    { key: "component", order: 1, binding: "COMPONENT_ID" },
    { key: "condition", order: 2, binding: "CONDITION_ID" },
  ];
  assert.deepEqual(orderedOperationalFields(fields).map(field => field.key), ["component", "condition", "evidence"]);
});

test("forms without evidence retain their exact configured order", () => {
  const fields = [{ key: "b", order: 2 }, { key: "a", order: 1 }];
  assert.deepEqual(orderedOperationalFields(fields).map(field => field.key), ["a", "b"]);
});

test("an existing evidence field is moved once, not duplicated", () => {
  const fields = [{ key: "evidence", order: 0, binding: "EVIDENCE_ATTACHMENTS" }, { key: "remarks", order: 1 }];
  const rendered = orderedOperationalFields(fields);
  assert.equal(rendered.filter(field => field.binding === "EVIDENCE_ATTACHMENTS").length, 1);
  assert.equal(rendered.at(-1).key, "evidence");
});

const imageField = { type: "IMAGE_UPLOAD", fileRules: { extensions: ["jpg", "jpeg", "png", "webp"], mimeTypes: ["image/jpeg", "image/png", "image/webp"], maxFileSizeBytes: 15 * 1024 * 1024 } };
const fakeImage = size => ({ name: "photo.jpg", type: "image/jpeg", size });

test("image size limit accepts below and exact 5 MiB and rejects larger", () => {
  assert.equal(maxBytesForFile(fakeImage(1), imageField), MAX_IMAGE_BYTES);
  assert.equal(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES - 1) }, imageField), null);
  assert.equal(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES) }, imageField), null);
  assert.match(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES + 1) }, imageField), /5 MB per-file limit/);
});

test("unsupported image extension and MIME are rejected", () => {
  assert.match(validateEvidenceFile({ name: "photo.gif", type: "image/gif", size: 100 }, imageField), /not a supported image type/);
});

test("mixed selection keeps valid images and rejects only the oversized file", () => {
  const exact = { name: "exact.png", type: "image/png", size: MAX_IMAGE_BYTES };
  const oversized = { name: "large.png", type: "image/png", size: MAX_IMAGE_BYTES + 1 };
  const valid = { name: "valid.png", type: "image/png", size: 12 };
  const result = partitionEvidenceFiles([exact, oversized, valid], imageField);
  assert.deepEqual(result.accepted, [exact, valid]);
  assert.deepEqual(result.rejected.map(item => item.file), [oversized]);
});
