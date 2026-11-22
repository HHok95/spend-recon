"use client";

import { Button } from "@ui-sdk/react/button";
import { useState } from "react";
import {
  approveJson,
  approveMatchRaw,
  chargeJson,
  createDocument,
  ensureSession,
  getMatch,
  isSlowDown,
  postTransaction,
  presetBook,
  problemDetail,
  receiptJson,
  reconcile,
  type CallResult,
} from "@/lib/api";
import { messageOf } from "@/lib/present";

type ChargeReplay = {
  body: string;
  key: string;
  id: string;
};

export function BreakIt({ ready, charge }: { ready: boolean; charge: ChargeReplay | null }) {
  const [replay, setReplay] = useState<{
    chargeId: string;
    status: number;
    id: string | null;
    same: boolean;
    detail: string;
  } | null>(null);
  const [race, setRace] = useState<CallResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"replay" | "race" | null>(null);

  async function runReplay() {
    if (!charge) {
      return;
    }
    setError(null);
    setBusy("replay");
    try {
      const session = await ensureSession();
      const result = await postTransaction(charge.body, charge.key, session);
      const id =
        result.json && typeof result.json === "object" && "id" in result.json
          ? String((result.json as { id?: unknown }).id ?? "")
          : null;
      setReplay({
        chargeId: charge.id,
        status: result.status,
        id,
        same: id === charge.id,
        detail: result.status === 201 ? "" : problemDetail(result.json, "The replay was refused."),
      });
    } catch (caught) {
      setError(isSlowDown(caught) ? "Slow down. The API is limiting requests." : messageOf(caught));
    } finally {
      setBusy(null);
    }
  }

  async function runRace() {
    setError(null);
    setBusy("race");
    setRace(null);
    try {
      const session = await ensureSession();
      const book = presetBook();
      const created = await postTransaction(
        chargeJson(book.charge),
        `race-charge-${crypto.randomUUID()}`,
        session,
      );
      if (created.status !== 201 || !created.json || typeof created.json !== "object" || !("id" in created.json)) {
        throw new Error(problemDetail(created.json, "The charge for this check was not recorded."));
      }
      const transactionId = String((created.json as { id: unknown }).id);
      await createDocument(receiptJson(book.receipt), `race-receipt-${crypto.randomUUID()}`, session);
      const matched = await reconcile(transactionId, `race-match-${crypto.randomUUID()}`, session);
      const match = await getMatch(matched.matchId, session);
      const body = approveJson(match.version);
      const results = await Promise.all(
        Array.from({ length: 10 }, (_, index) =>
          approveMatchRaw(match.matchId, body, `race-${index + 1}-${crypto.randomUUID()}`, session),
        ),
      );
      setRace(results);
    } catch (caught) {
      setError(isSlowDown(caught) ? "Slow down. The API is limiting requests." : messageOf(caught));
    } finally {
      setBusy(null);
    }
  }

  const shownReplay = replay && charge && replay.chargeId === charge.id ? replay : null;
  const stored = race?.filter((result) => result.status >= 200 && result.status < 300).length ?? 0;
  const conflicts = race?.filter((result) => result.status === 409).length ?? 0;

  return (
    <section className="panel" id="break-it">
      <h2>Try to break it</h2>
      <p className="lead">Repeat one request, then send ten approvals at the same match.</p>

      <div className="check">
        <h3>Replay</h3>
        <p>The charge is sent again with the same Idempotency-Key and the same body.</p>
        <Button
          data-variant="solid"
          disabled={!ready || !charge || busy !== null}
          pendingAnnouncement="Replaying the charge"
          onClick={runReplay}
        >
          Replay the charge
        </Button>
        {!charge ? <p className="muted">Record the charge in the walkthrough first.</p> : null}
        {shownReplay ? (
          <dl className="facts">
            <div>
              <dt>Status</dt>
              <dd>{shownReplay.status}</dd>
            </div>
            <div>
              <dt>Original id</dt>
              <dd>{charge?.id}</dd>
            </div>
            <div>
              <dt>Replay id</dt>
              <dd>{shownReplay.id ?? "none"}</dd>
            </div>
            <div>
              <dt>Result</dt>
              <dd>
                {shownReplay.same
                  ? "The same id came back. The original response was replayed."
                  : shownReplay.detail || "The replay did not return the original id."}
              </dd>
            </div>
          </dl>
        ) : null}
      </div>

      <div className="check">
        <h3>Ten at once</h3>
        <p>Ten approvals hit one new match at the same version. One is stored. The rest are refused.</p>
        <Button
          data-variant="solid"
          disabled={!ready || busy !== null}
          pendingAnnouncement="Sending ten approvals"
          onClick={runRace}
        >
          Fire 10 approvals
        </Button>
        {race ? (
          <>
            <p>
              {stored} stored. {conflicts} returned 409.
            </p>
            <ol className="race">
              {race.map((result, index) => (
                <li key={result.idempotencyKey ?? index}>
                  <span>{result.status}</span>
                  <span>Retry-After: {result.retryAfter ?? "none"}</span>
                  <span>
                    {result.status >= 200 && result.status < 300
                      ? "Stored."
                      : problemDetail(result.json, "Refused.")}
                  </span>
                </li>
              ))}
            </ol>
          </>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}
