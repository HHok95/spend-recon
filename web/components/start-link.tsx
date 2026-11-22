"use client";

import { Button } from "@ui-sdk/react/button";

export function StartLink() {
  return (
    <Button data-variant="solid" render={<a href="#walkthrough" />}>
      Start the walkthrough
    </Button>
  );
}
