"use client";

import { useCallback, useState } from "react";
import { BreakIt } from "@/components/break-it";
import { Walkthrough } from "@/components/walkthrough";
import { Warmup } from "@/components/warmup";

type ChargeReplay = {
  body: string;
  key: string;
  id: string;
};

export function Demo() {
  const [ready, setReady] = useState(false);
  const [charge, setCharge] = useState<ChargeReplay | null>(null);
  const onReady = useCallback((value: boolean) => {
    setReady(value);
  }, []);

  return (
    <>
      <Warmup onReady={onReady} />
      <Walkthrough ready={ready} onCharge={setCharge} />
      <BreakIt ready={ready} charge={charge} />
    </>
  );
}
