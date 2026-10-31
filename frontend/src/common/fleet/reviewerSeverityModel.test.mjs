import test from "node:test";
import assert from "node:assert/strict";
import { initialReviewerSeverityId, reviewerSeverityChange } from "./reviewerSeverityModel.mjs";

const severities = [{id:"s-low",name:"Low"},{id:"s-major",name:"Major"}];

test("initial reviewer severity inherits the immutable report snapshot", () => {
  assert.equal(initialReviewerSeverityId({hasSubmittedReport:true,snapshotSeverityId:"s-low",defectSeverityName:"Major",severities}),"s-low");
});

test("a legacy report without a severity remains unselected instead of borrowing a different current value", () => {
  assert.equal(initialReviewerSeverityId({hasSubmittedReport:true,snapshotSeverityId:null,defectSeverityName:"Major",severities}),"");
});

test("legacy defects without a form submission can initialize from the current tenant reference", () => {
  assert.equal(initialReviewerSeverityId({hasSubmittedReport:false,snapshotSeverityId:null,defectSeverityName:"Major",severities}),"s-major");
});

test("keeping the original severity requires no change reason", () => {
  assert.deepEqual(reviewerSeverityChange("s-low","s-low"),{changed:false,reasonRequired:false,reasonValid:true});
});

test("changing severity requires a nonblank reason", () => {
  assert.deepEqual(reviewerSeverityChange("s-low","s-major","  "),{changed:true,reasonRequired:true,reasonValid:false});
  assert.deepEqual(reviewerSeverityChange("s-low","s-major","Evidence supports escalation."),{changed:true,reasonRequired:true,reasonValid:true});
});
