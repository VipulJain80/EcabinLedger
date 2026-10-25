import assert from "node:assert/strict";
import test from "node:test";
import { orderedOperationalFields } from "./publishedFormDefinition.ts";
import { BYTES_PER_MEGABYTE, MAX_IMAGE_BYTES, maxBytesForFile, maxTotalBytesForField, partitionEvidenceFiles, partitionEvidenceSelection, validateEvidenceFile } from "./evidenceFileRules.ts";

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

const imageField = { type: "IMAGE_UPLOAD", fileRules: { extensions: ["jpg", "jpeg", "png", "webp"], mimeTypes: ["image/jpeg", "image/png", "image/webp"], maxFileSizeBytes: 20 * BYTES_PER_MEGABYTE, minCount: 1, maxCount: 3, maxTotalSizeBytes: 30 * BYTES_PER_MEGABYTE } };
const fakeImage = size => ({ name: "photo.jpg", type: "image/jpeg", size });

test("IMAGE_UPLOAD per-file limit accepts exactly 20 MiB and rejects one byte over", () => {
  assert.equal(maxBytesForFile(fakeImage(1), imageField), MAX_IMAGE_BYTES);
  assert.equal(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES - 1) }, imageField), null);
  assert.equal(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES) }, imageField), null);
  assert.match(validateEvidenceFile({ ...fakeImage(MAX_IMAGE_BYTES + 1) }, imageField), /per-file limit of 20 MB/);
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

test("IMAGE_UPLOAD accepts exactly 30 MiB across a selection and rejects aggregate overflow", () => {
  const ten = [fakeImage(10 * BYTES_PER_MEGABYTE), fakeImage(10 * BYTES_PER_MEGABYTE), fakeImage(10 * BYTES_PER_MEGABYTE)];
  assert.equal(partitionEvidenceSelection([], ten, imageField).accepted.length, 3);
  const twentyPlusTen = [fakeImage(20 * BYTES_PER_MEGABYTE), fakeImage(10 * BYTES_PER_MEGABYTE)];
  assert.equal(partitionEvidenceSelection([], twentyPlusTen, imageField).rejected.length, 0);
  const over = partitionEvidenceSelection([], [fakeImage(20 * BYTES_PER_MEGABYTE), fakeImage(11 * BYTES_PER_MEGABYTE)], imageField);
  assert.equal(over.accepted.length, 0);
  assert.equal(over.rejected.length, 2);
  assert.match(over.rejected[0].message, /Combined upload size exceeds the configured total limit of 30 MB/);
});

test("individual size and maximum count remain independent of the aggregate limit", () => {
  const individual = partitionEvidenceSelection([], [fakeImage(21 * BYTES_PER_MEGABYTE)], imageField);
  assert.match(individual.rejected[0].message, /per-file limit of 20 MB/);
  const tooMany = partitionEvidenceSelection([], [fakeImage(1),fakeImage(1),fakeImage(1),fakeImage(1)], imageField);
  assert.equal(tooMany.accepted.length, 0);
  assert.equal(tooMany.rejected.length, 4);
  assert.match(tooMany.rejected[0].message, /no more than 3 file/);
});

test("removing or replacing selected files recalculates combined size", () => {
  const existing=[{sizeBytes:20*BYTES_PER_MEGABYTE},{sizeBytes:10*BYTES_PER_MEGABYTE}];
  assert.equal(partitionEvidenceSelection(existing,[fakeImage(1)],imageField).rejected.length,1);
  assert.equal(partitionEvidenceSelection([existing[0]],[fakeImage(10*BYTES_PER_MEGABYTE)],imageField).rejected.length,0);
  assert.equal(partitionEvidenceSelection([{sizeBytes:10*BYTES_PER_MEGABYTE}],[fakeImage(20*BYTES_PER_MEGABYTE)],imageField).rejected.length,0);
});

test("FILE_UPLOAD uses the same independent size and aggregate rules without image-only limits", () => {
  const fileField={type:"FILE_UPLOAD",fileRules:{extensions:["pdf","png"],mimeTypes:["application/pdf","image/png"],maxFileSizeBytes:20*BYTES_PER_MEGABYTE,minCount:1,maxCount:3,maxTotalSizeBytes:30*BYTES_PER_MEGABYTE}};
  const pdf={name:"manual.pdf",type:"application/pdf",size:20*BYTES_PER_MEGABYTE};
  assert.equal(validateEvidenceFile(pdf,fileField),null);
  const selection=partitionEvidenceSelection([],[pdf,{name:"next.pdf",type:"application/pdf",size:11*BYTES_PER_MEGABYTE}],fileField);
  assert.equal(selection.accepted.length,0);
  assert.equal(selection.rejected.length,2);
  assert.match(selection.rejected[0].message,/30 MB/);
});

test("legacy form definitions without a total limit preserve their prior per-file-times-count ceiling", () => {
  const legacy={type:"FILE_UPLOAD",fileRules:{extensions:["pdf"],mimeTypes:["application/pdf"],maxFileSizeBytes:5*BYTES_PER_MEGABYTE,minCount:0,maxCount:3}};
  assert.equal(maxTotalBytesForField(legacy),15*BYTES_PER_MEGABYTE);
  assert.equal(partitionEvidenceSelection([],Array.from({length:3},(_,index)=>({name:`${index}.pdf`,type:"application/pdf",size:5*BYTES_PER_MEGABYTE})),legacy).rejected.length,0);
});

test("Report Master exposes, validates, and sends the total setting for both upload field types", async () => {
  const source=await import("node:fs/promises").then(fs=>fs.readFile(new URL("../configuration/FormMasterPage.tsx",import.meta.url),"utf8"));
  assert.match(source,/type==="FILE_UPLOAD"\|\|type==="IMAGE_UPLOAD"/);
  assert.match(source,/maxTotalSizeBytes:30\*1024\*1024/);
  assert.match(source,/Total upload size \(MB\)/);
  assert.match(source,/JSON\.stringify\(\{configuration:config\}\)/);
  assert.match(source,/maxTotalSizeBytes!=null/);
});
