"use client";

import { Button } from "@ui-sdk/react/button";
import { Progress, ProgressIndicator, ProgressTrack } from "@ui-sdk/react/progress";
import { useEffect, useState } from "react";
import { ApiError, getHealth, isSlowDown } from "@/lib/api";

type Phase = "warming" | "ready" | "slow" | "failed";

const DEADLINE_MS = 45_000;

function sleep(ms: number) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

export function Warmup({ onReady }: { onReady: (ready: boolean) => void }) {
  const [phase, setPhase] = useState<Phase>("warming");
  const [attempt, setAttempt] = useState(0);
  const [detail, setDetail] = useState<string | null>(null);

  useEffect(() => {
    onReady(phase === "ready");
  }, [onReady, phase]);

  useEffect(() => {
    let cancelled = false;
    const started = Date.now();

    async function run() {
      setPhase("warming");
      setDetail(null);
      let delay = 0;
      while (Date.now() - started < DEADLINE_MS) {
        const remaining = DEADLINE_MS - (Date.now() - started);
        if (delay > 0) {
          await sleep(Math.min(delay, remaining));
        }
        if (cancelled || Date.now() - started >= DEADLINE_MS) {
          break;
        }
        try {
          const up = await getHealth();
          if (cancelled) {
            return;
          }
          if (up) {
            setPhase("ready");
            return;
          }
        } catch (error) {
          if (cancelled) {
            return;
          }
          if (isSlowDown(error)) {
            setPhase("slow");
            setDetail(error instanceof ApiError && error.retryAfter ? `Retry-After: ${error.retryAfter}` : null);
            return;
          }
          if (error instanceof ApiError && error.status === 0 && error.message.includes("NEXT_PUBLIC_API_URL")) {
            setPhase("failed");
            setDetail(error.message);
            return;
          }
        }
        delay = delay === 0 ? 2000 : Math.min(delay * 2, 8000);
      }
      if (!cancelled) {
        setPhase("failed");
      }
    }

    void run();
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  if (phase === "ready") {
    return null;
  }

  return (
    <section className="warmup" aria-live="polite">
      {phase === "warming" ? (
        <>
          <p id="warmup-status">Warming up the live demo, about 10 seconds</p>
          <Progress aria-labelledby="warmup-status" value={null}>
            <ProgressTrack>
              <ProgressIndicator />
            </ProgressTrack>
          </Progress>
        </>
      ) : null}
      {phase === "slow" ? (
        <>
          <p role="alert">Slow down. The API is limiting requests.</p>
          {detail ? <p className="muted">{detail}</p> : null}
          <Button onClick={() => setAttempt((value) => value + 1)}>Try again</Button>
        </>
      ) : null}
      {phase === "failed" ? (
        <>
          <p role="alert">
            {detail ??
              "The live demo did not wake up. The first request can take a while after the service has been idle."}
          </p>
          <Button onClick={() => setAttempt((value) => value + 1)}>Try again</Button>
        </>
      ) : null}
    </section>
  );
}
