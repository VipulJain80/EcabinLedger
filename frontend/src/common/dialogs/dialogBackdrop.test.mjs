import test from "node:test";
import assert from "node:assert/strict";
import { handleDialogBackdropMouseDown } from "./dialogBackdrop.ts";

function mouseEvent(target, currentTarget) {
  const calls = { prevented: false, stopped: false };
  return {
    calls,
    event: {
      target,
      currentTarget,
      preventDefault() { calls.prevented = true; },
      stopPropagation() { calls.stopped = true; },
    },
  };
}

test("backdrop press is consumed without dismissing or clicking through", () => {
  const backdrop = {};
  const { event, calls } = mouseEvent(backdrop, backdrop);
  handleDialogBackdropMouseDown(event);
  assert.deepEqual(calls, { prevented: true, stopped: true });
});

test("backdrop click is consumed without dismissing the dialog", () => {
  const backdrop = {};
  const { event, calls } = mouseEvent(backdrop, backdrop);
  handleDialogBackdropMouseDown(event);
  assert.deepEqual(calls, { prevented: true, stopped: true });
});

test("press inside the dialog does not invoke backdrop behavior", () => {
  const { event, calls } = mouseEvent({}, {});
  handleDialogBackdropMouseDown(event);
  assert.deepEqual(calls, { prevented: false, stopped: false });
});
