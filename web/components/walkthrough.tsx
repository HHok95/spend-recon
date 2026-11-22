"use client";

import { Button } from "@ui-sdk/react/button";
import { Timeline, TimelineConnector, TimelineDescription, TimelineItem, TimelineMarker, TimelineTime, TimelineTitle } from "@ui-sdk/react/timeline";
import { useState } from "react";
import { Hood } from "@/components/hood";
import {
  approveJson,
  approveMatch,
  chargeJson,
  createDocument,
  createTransaction,
  ensureSession,
  exportApproved,
  getHistory,
  getMatch,
  isSlowDown,
  presetBook,
  receiptJson,
  reconcile,
  type HistoryEvent,
  type Match,
  type PresetBook,
} from "@/lib/api";
import { eventVersion, formatScore, formatWhen, messageOf, outboxLine, versionLine } from "@/lib/present";

type StepId = "charge" | "receipt" | "match" | "approve" | "export" | "audit";

type HoodInfo = {
  idempotencyKey: string | null;
  version: string;
  outbox: string;
};

type Done = {
  sentence: string;
  facts: { label: string; value: string }[];
  hood: HoodInfo;
  mocked?: boolean;
  csv?: string;
  events?: HistoryEvent[];
};

type Run = {
  chargeBody: string;
  chargeKey: string;
  chargeId: string;
  documentId: string;
  match?: Match;
};

const STEPS: { id: StepId; title: string; idle: string; action: string }[] = [
  {
    id: "charge",
    title: "Charge",
    idle: "A card charge arrives, with the amount sent as a decimal string.",
    action: "Record the charge",
  },
  {
    id: "receipt",
    title: "Receipt",
    idle: "The receipt for that charge is attached.",
    action: "Attach the receipt",
  },
  {
    id: "match",
    title: "Auto-match",
    idle: "The receipt is matched to the charge, and the status and reason come back.",
    action: "Match the receipt",
  },
  {
    id: "approve",
    title: "Approve",
    idle: "The matched item is approved at its current version.",
    action: "Approve the match",
  },
  {
    id: "export",
    title: "Export",
    idle: "Approved items are exported as CSV.",
    action: "Export approved items",
  },
  {
    id: "audit",
    title: "Audit timeline",
    idle: "Every event is listed with its actor, action, time, and version.",
    action: "Read the timeline",
  },
];

function downloadCsv(csv: string) {
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "approved.csv";
  link.click();
  URL.revokeObjectURL(url);
}

export function Walkthrough({
  ready,
  onCharge,
}: {
  ready: boolean;
  onCharge: (charge: { body: string; key: string; id: string } | null) => void;
}) {
  const [book, setBook] = useState<PresetBook | null>(null);
  const [done, setDone] = useState<Partial<Record<StepId, Done>>>({});
  const [run, setRun] = useState<Run | null>(null);
  const [busy, setBusy] = useState<StepId | null>(null);
  const [error, setError] = useState<string | null>(null);

  const current = STEPS.find((step) => !done[step.id])?.id ?? null;

  async function runStep(id: StepId) {
    setError(null);
    setBusy(id);
    try {
      const session = await ensureSession();
      const active = book ?? (id === "charge" ? presetBook() : null);
      if (!active) {
        throw new Error("Record the charge first.");
      }
      if (!book) {
        setBook(active);
      }
      if (id === "charge") {
        const body = chargeJson(active.charge);
        const key = `charge-${crypto.randomUUID()}`;
        const created = await createTransaction(body, key, session);
        const next: Run = {
          chargeBody: body,
          chargeKey: key,
          chargeId: created.id,
          documentId: "",
        };
        setRun(next);
        onCharge({ body, key, id: created.id });
        setDone((previous) => ({
          ...previous,
          charge: {
            sentence: `A charge at Northwind Coffee for 18.40 AUD is recorded as ${created.status}.`,
            facts: [
              { label: "Id", value: created.id },
              { label: "Status", value: created.status },
            ],
            hood: {
              idempotencyKey: key,
              version: "This response does not include a version.",
              outbox: "This response does not include an outbox event.",
            },
          },
        }));
        return;
      }

      if (!run) {
        throw new Error("Record the charge first.");
      }

      if (id === "receipt") {
        const body = receiptJson(active.receipt);
        const key = `receipt-${crypto.randomUUID()}`;
        const created = await createDocument(body, key, session);
        setRun({ ...run, documentId: created.id });
        setDone((previous) => ({
          ...previous,
          receipt: {
            sentence: "The receipt for Northwind Coffee, 18.40 AUD, is attached.",
            facts: [{ label: "Id", value: created.id }],
            hood: {
              idempotencyKey: key,
              version: "This response does not include a version.",
              outbox: "This response does not include an outbox event.",
            },
          },
        }));
        return;
      }

      if (id === "match") {
        const key = `match-${crypto.randomUUID()}`;
        const result = await reconcile(run.chargeId, key, session);
        const match = await getMatch(result.matchId, session);
        setRun({ ...run, match });
        const rules = match.rulesFired.length > 0 ? match.rulesFired.join(", ") : "none";
        setDone((previous) => ({
          ...previous,
          match: {
            sentence:
              match.status === "SUGGESTED"
                ? `The receipt matches the charge as a suggestion, score ${formatScore(match.score)}. A person still approves it.`
                : match.status === "EXCEPTION"
                  ? `The charge was not matched. Status is EXCEPTION${match.exceptionType ? `, ${match.exceptionType}` : ""}.`
                  : `The receipt matches the charge. Status is ${match.status}, score ${formatScore(match.score)}.`,
            facts: [
              { label: "Status", value: match.status },
              { label: "Score", value: formatScore(match.score) },
              { label: "Rules", value: rules },
              ...(match.exceptionType ? [{ label: "Reason", value: match.exceptionType }] : []),
              { label: "Version", value: String(match.version) },
            ],
            hood: {
              idempotencyKey: key,
              version: versionLine(match, match.version),
              outbox: outboxLine(result),
            },
          },
        }));
        return;
      }

      if (id === "approve") {
        const match = run.match;
        if (!match) {
          throw new Error("Match the receipt first.");
        }
        if (match.status === "APPROVED") {
          setDone((previous) => ({
            ...previous,
            approve: {
              sentence: `The service already approved this match at version ${match.version}.`,
              facts: [
                { label: "Status", value: match.status },
                { label: "Version", value: String(match.version) },
                { label: "Decided by", value: match.decidedBy ?? "unknown" },
              ],
              hood: {
                idempotencyKey: null,
                version: String(match.version),
                outbox: "This response does not include an outbox event.",
              },
            },
          }));
          return;
        }
        const body = approveJson(match.version);
        const key = `approve-${crypto.randomUUID()}`;
        const decision = await approveMatch(match.matchId, body, key, session);
        const updated = await getMatch(match.matchId, session);
        setRun({ ...run, match: updated });
        setDone((previous) => ({
          ...previous,
          approve: {
            sentence: `The match is approved at version ${decision.version}.`,
            facts: [
              { label: "Status", value: decision.status },
              { label: "Version", value: String(decision.version) },
            ],
            hood: {
              idempotencyKey: key,
              version: versionLine(decision, decision.version),
              outbox: outboxLine(decision),
            },
          },
        }));
        return;
      }

      if (id === "export") {
        const match = run.match;
        if (!match) {
          throw new Error("Match the receipt first.");
        }
        const exported = await exportApproved(session, {
          matchId: match.matchId,
          transactionId: match.transactionId,
          documentId: match.documentId,
          status: match.status,
          score: match.score,
          version: match.version,
        });
        setDone((previous) => ({
          ...previous,
          export: {
            sentence: "The approved items are in this CSV.",
            facts: [],
            csv: exported.csv,
            mocked: exported.mocked,
            hood: {
              idempotencyKey: null,
              version: String(match.version),
              outbox: "This response does not include an outbox event.",
            },
          },
        }));
        return;
      }

      const match = run.match;
      if (!match) {
        throw new Error("Match the receipt first.");
      }
      const events = await getHistory(match.matchId, session);
      setDone((previous) => ({
        ...previous,
        audit: {
          sentence: "Every event for this match is below.",
          facts: [{ label: "Events", value: String(events.length) }],
          events,
          hood: {
            idempotencyKey: null,
            version: String(match.version),
            outbox: "This response does not include an outbox event.",
          },
        },
      }));
    } catch (caught) {
      setError(slowMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  function reset() {
    setDone({});
    setRun(null);
    setError(null);
    onCharge(null);
    setBook(null);
  }

  return (
    <section id="walkthrough" className="panel">
      <h2>Walkthrough</h2>
      <p className="lead">Six steps. Each click calls the live API.</p>
      <ol className="steps">
        {STEPS.map((step, index) => {
          const result = done[step.id];
          const isCurrent = current === step.id;
          return (
            <li key={step.id} data-current={isCurrent ? "true" : undefined}>
              <h3>
                <span>{index + 1}</span>
                {step.title}
              </h3>
              <p>{result?.sentence ?? step.idle}</p>
              {step.id === "charge" && !result ? (
                <p className="preset">Northwind Coffee · 18.40 AUD</p>
              ) : null}
              {step.id === "receipt" && !result ? (
                <p className="preset">Receipt · Northwind Coffee · 18.40 AUD</p>
              ) : null}
              {result && result.facts.length > 0 ? (
                <dl className="facts">
                  {result.facts.map((fact) => (
                    <div key={fact.label}>
                      <dt>{fact.label}</dt>
                      <dd>{fact.value}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}
              {result?.csv ? (
                <div className="csv">
                  {result.mocked ? (
                    <p className="note">Dev stand-in. GET /demo/export is not on this API yet.</p>
                  ) : null}
                  <pre>{result.csv}</pre>
                  <Button data-variant="solid" onClick={() => downloadCsv(result.csv ?? "")}>
                    Download CSV
                  </Button>
                </div>
              ) : null}
              {result?.events ? <AuditList events={result.events} /> : null}
              {result ? (
                <Hood
                  idempotencyKey={result.hood.idempotencyKey}
                  version={result.hood.version}
                  outbox={result.hood.outbox}
                />
              ) : null}
              {isCurrent ? (
                <Button
                  data-variant="solid"
                  disabled={!ready || busy !== null}
                  pendingAnnouncement={step.action}
                  onClick={() => runStep(step.id)}
                >
                  {step.action}
                </Button>
              ) : null}
              {isCurrent && error ? (
                <p className="alert" role="alert">
                  {error}
                </p>
              ) : null}
              {!ready && isCurrent ? <p className="muted">Waiting for the API.</p> : null}
            </li>
          );
        })}
      </ol>
      {Object.keys(done).length > 0 ? (
        <Button
          onClick={() => {
            reset();
          }}
        >
          Start over
        </Button>
      ) : null}
    </section>
  );
}

function slowMessage(error: unknown): string {
  if (isSlowDown(error)) {
    return "Slow down. The API is limiting requests.";
  }
  return messageOf(error);
}

function AuditList({ events }: { events: HistoryEvent[] }) {
  return (
    <Timeline aria-label="Audit timeline">
      {events.map((event) => (
        <TimelineItem key={event.id}>
          <TimelineMarker />
          <TimelineConnector />
          <TimelineTitle>{event.eventType}</TimelineTitle>
          <TimelineTime dateTime={event.createdAt}>{formatWhen(event.createdAt)}</TimelineTime>
          <TimelineDescription>
            {event.actor} · version {eventVersion(event)}
            {event.reason ? `. ${event.reason}` : ""}
          </TimelineDescription>
        </TimelineItem>
      ))}
    </Timeline>
  );
}
