import assert from "node:assert/strict";
import { test } from "node:test";
import type { HistoryEvent } from "./api";
import { eventCountLabel, timelineText } from "./present.ts";

function event(partial: Partial<HistoryEvent> = {}): HistoryEvent {
  return {
    id: 1,
    eventType: "MATCHED",
    actor: "system:matching",
    reason: null,
    rulesFired: [],
    before: null,
    after: null,
    createdAt: "2026-01-15T00:00:00Z",
    ...partial,
  };
}

test("timeline text drops the version when the event has none", () => {
  assert.equal(timelineText(event()), "system:matching");
  assert.equal(timelineText(event({ reason: "held for review" })), "system:matching. held for review");
});

test("timeline text keeps a version that is present", () => {
  assert.equal(timelineText(event({ after: { version: 1 } })), "system:matching · version 1");
  assert.equal(
    timelineText(event({ before: { version: 0 }, actor: "demo-reviewer", reason: "Receipt matches the charge." })),
    "demo-reviewer · version 0. Receipt matches the charge.",
  );
});

test("event count label uses the singular only for one event", () => {
  assert.equal(eventCountLabel(0), "0 events");
  assert.equal(eventCountLabel(1), "1 event");
  assert.equal(eventCountLabel(2), "2 events");
});
