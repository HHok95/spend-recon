/* Posters and beat stills stay native images so their loading strategy stays explicit. */
/* eslint-disable @next/next/no-img-element */
import { Scroller } from "@/components/landing/scroller";

const REPO = "https://github.com/HHok95/spend-recon";

function Actions() {
  return (
    <div className="actions">
      <a className="btn" href="/demo">
        Open the demo →
      </a>
      <a className="link" href={REPO} target="_blank" rel="noopener noreferrer">
        Read the code
      </a>
    </div>
  );
}

export function Landing({ fontClass }: { fontClass: string }) {
  return (
    <div className={`landing ${fontClass}`} data-landing="">
      <Scroller />
      <header className="top">
        <span className="word">Hok Heng · Software Engineer</span>
        <nav aria-label="Site">
          <a href={REPO} target="_blank" rel="noopener noreferrer">
            GitHub
          </a>
          <a href="/demo">Demo</a>
        </nav>
      </header>
      <canvas className="stage" aria-hidden="true" />
      <picture>
        <source media="(min-width: 768px)" srcSet="/landing/hero.webp" type="image/webp" />
        <img
          className="poster"
          alt=""
          src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        />
      </picture>
      <div className="frames" aria-hidden="true">
        <img data-frame="0" data-on="1" alt="" src="/landing/hero.webp" loading="lazy" decoding="async" />
        <img data-frame="1" data-on="0" alt="" src="/landing/frame-2.webp" loading="lazy" decoding="async" />
        <img data-frame="2" data-on="0" alt="" src="/landing/frame-3.webp" loading="lazy" decoding="async" />
        <img data-frame="3" data-on="0" alt="" src="/landing/frame-4.webp" loading="lazy" decoding="async" />
        <img data-frame="4" data-on="0" alt="" src="/landing/frame-5.webp" loading="lazy" decoding="async" />
        <img data-frame="5" data-on="0" alt="" src="/landing/frame-6.webp" loading="lazy" decoding="async" />
      </div>
      <main>
        <p className="sr-only">
          Out of the 3-day window: Cafe, AUD 8.00, 2026-01-11, and Officeworks, AUD 128.00, 2026-01-21. Northwind
          Coffee scores <span className="token">0.700</span> at version 0. The stored approval is version 1. INV-100
          twice is refused. Approving an exception without a reason is 422. The approver is{" "}
          <span className="token">demo-reviewer</span>. Seeded book: Dwayne 6.50 <span className="token">MATCHED</span>,
          Cafe 8.00 <span className="token">SUGGESTED</span> 0.700, Officeworks 120.00{" "}
          <span className="token">AMOUNT_MISMATCH</span>, Uber 24.30 <span className="token">NO_CANDIDATE</span>.
        </p>
        <section className="beat beat-hero" data-beat="0">
          <figure className="shot">
            <img src="/landing/m-1.webp" alt="" width={780} height={585} fetchPriority="high" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="eyebrow reveal" data-reveal="0">
                Kotlin + Spring Boot API · by Hok Heng
              </p>
              <h1 className="reveal" data-reveal="1">
                Card spend, matched to its receipt.
              </h1>
              <p className="lede reveal" data-reveal="2">
                Each card charge is scored against its receipts. Strong matches clear on their own, the rest wait for a
                person, and every change is on the record.
              </p>
              <div className="reveal" data-reveal="3">
                <Actions />
              </div>
            </div>
          </div>
        </section>
        <section className="beat" data-beat="1">
          <figure className="shot">
            <img src="/landing/m-2.webp" alt="" width={780} height={585} loading="lazy" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="step reveal" data-reveal="0">
                <span>01</span>Charge
              </p>
              <h2 className="reveal" data-reveal="1">
                A card charge comes in.
              </h2>
              <p className="body reveal" data-reveal="2">
                Woolworths, AUD 54.80 with 4.98 tax. It&apos;s stored once per Idempotency-Key and starts{" "}
                <span className="token">UNMATCHED</span>.
              </p>
              <p className="fact reveal" data-reveal="3">
                <span>
                  POST /transactions · <b>201</b>
                </span>
                <span>UNMATCHED</span>
              </p>
              <p className="tag reveal" data-reveal="4">
                From the seeded sandbox book.
              </p>
            </div>
          </div>
        </section>
        <section className="beat" data-beat="2">
          <figure className="shot">
            <img src="/landing/m-3.webp" alt="" width={780} height={585} loading="lazy" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="step reveal" data-reveal="0">
                <span>02</span>Match
              </p>
              <h2 className="reveal" data-reveal="1">
                One receipt, one top score.
              </h2>
              <p className="body reveal" data-reveal="2">
                Receipts within 3 days are scored on merchant name and tax. Woolworths scores 1.000. One top score at
                0.850 or above, with no tie, is <span className="token">MATCHED</span>.
              </p>
              <p className="fact reveal" data-reveal="3">
                <span className="ok">
                  <i aria-hidden="true" />
                  MATCHED
                </span>
                <span>
                  <b>1.000</b> ≥ 0.850
                </span>
                <span>no tie</span>
              </p>
              <p className="tag reveal" data-reveal="4">
                A tie or a lower score is SUGGESTED. At 0.95 or above it&apos;s approved automatically.
              </p>
            </div>
          </div>
        </section>
        <section className="beat" data-beat="3">
          <figure className="shot">
            <img src="/landing/m-4.webp" alt="" width={780} height={585} loading="lazy" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="step reveal" data-reveal="0">
                <span>03</span>Concurrency
              </p>
              <h2 className="reveal" data-reveal="1">
                Ten approvals at once. One lands.
              </h2>
              <p className="body reveal" data-reveal="2">
                Ten requests approve the same match at version 0. @Version lets one through. The other nine get 409 and
                reload.
              </p>
              <p className="fact reveal" data-reveal="3">
                <span className="ok">
                  <i aria-hidden="true" />1 × 200
                </span>
                <span className="bad">
                  <i aria-hidden="true" />9 × 409
                </span>
                <span className="quiet">Retry-After: 1 if the row is busy</span>
              </p>
            </div>
          </div>
        </section>
        <section className="beat" data-beat="4">
          <figure className="shot">
            <img src="/landing/m-5.webp" alt="" width={780} height={585} loading="lazy" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="step reveal" data-reveal="0">
                <span>04</span>Exception
              </p>
              <h2 className="reveal" data-reveal="1">
                A possible duplicate waits for a person.
              </h2>
              <p className="body reveal" data-reveal="2">
                A second receipt with the same merchant, amount and date is held as an{" "}
                <span className="token">EXCEPTION: POSSIBLE_DUPLICATE</span>. Approving it needs a reason.
              </p>
              <p className="fact reveal" data-reveal="3">
                <span className="bad">
                  <i aria-hidden="true" />
                  EXCEPTION
                </span>
                <span>R7_DUPLICATE</span>
                <span>reason required</span>
              </p>
              <p className="tag reveal" data-reveal="4">
                The same receipt number twice is refused: 409 Duplicate document. R7 is merged (PR #10).
              </p>
            </div>
          </div>
        </section>
        <section className="beat beat-last" data-beat="5">
          <figure className="shot">
            <img src="/landing/m-6.webp" alt="" width={780} height={585} loading="lazy" decoding="async" />
          </figure>
          <div className="copy">
            <div className="copy-inner">
              <p className="step reveal" data-reveal="0">
                <span>05</span>Export
              </p>
              <h2 className="reveal" data-reveal="1">
                Exported, with the trail.
              </h2>
              <p className="body reveal" data-reveal="2">
                Approved matches go out as CSV. Every status change is an append-only audit row.
              </p>
              <ol className="log reveal" data-reveal="3">
                <li>
                  <span className="item">Woolworths</span>
                  <b className="ok">MATCHED</b>
                  <span className="actor">system:matching</span>
                </li>
                <li>
                  <span className="item">Woolworths</span>
                  <b className="ok">APPROVED</b>
                  <span className="actor">system:auto-approve</span>
                </li>
                <li>
                  <span className="item">Woolworths</span>
                  <b className="ok">MatchConfirmed</b>
                  <span className="actor">outbox · published</span>
                </li>
                <li>
                  <span className="item">Officeworks</span>
                  <b className="bad">EXCEPTION</b>
                  <span className="actor">AMOUNT_MISMATCH</span>
                </li>
              </ol>
              <div className="reveal" data-reveal="4">
                <Actions />
              </div>
            </div>
          </div>
        </section>
        <p className="footnote">
          Illustrative: the card (HokWallet •• 4417), the month-end framing, and which of the ten approvals wins (#07).
          Beat 4 uses the live demo preset (Northwind Coffee, AUD 18.40). Beat 5 uses the R7 test receipts in PR #10
          (Cafe, AUD 12.34, R-1 / R-2). Everything else comes from scripts/seed.sh.
        </p>
      </main>
    </div>
  );
}
