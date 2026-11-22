"use client";

import { Collapsible, CollapsiblePanel, CollapsibleTrigger } from "@ui-sdk/react/collapsible";

export function Hood({
  idempotencyKey,
  version,
  outbox,
}: {
  idempotencyKey: string | null;
  version: string;
  outbox: string;
}) {
  return (
    <Collapsible className="hood">
      <CollapsibleTrigger>What&apos;s happening under the hood</CollapsibleTrigger>
      <CollapsiblePanel>
        <dl>
          <div>
            <dt>Idempotency-Key</dt>
            <dd>{idempotencyKey ?? "This request does not use one."}</dd>
          </div>
          <div>
            <dt>Version</dt>
            <dd>{version}</dd>
          </div>
          <div>
            <dt>Outbox event</dt>
            <dd>{outbox}</dd>
          </div>
        </dl>
      </CollapsiblePanel>
    </Collapsible>
  );
}
