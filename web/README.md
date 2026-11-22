# spend-recon web

`/` is the landing page. `/demo` is the public walkthrough for the spend-recon API. The walkthrough page is static. The browser calls the API after it loads.

## Local

Node.js 22.23.4.

```bash
cd web
npm ci
cp .env.example .env.local
```

Point `.env.local` at the API on this machine and turn on the two local-only settings. Do not set either of those on Vercel.

```bash
NEXT_PUBLIC_API_URL=http://127.0.0.1:8080
NEXT_PUBLIC_DEV_API_KEY=local-dev-key
NEXT_PUBLIC_DEMO_MOCK=1
```

`NEXT_PUBLIC_DEV_API_KEY` is the Compose default, sent as `X-API-Key` when demo mode is off. `NEXT_PUBLIC_DEV_API_KEY` is dev-only. A production build ignores it. `NEXT_PUBLIC_DEMO_MOCK=1` stands in for `POST /demo/session` and `GET /demo/export` while `next dev` is running. A production build ignores the flag.

```bash
npm run dev
```

Open http://localhost:3000 for the landing page. The walkthrough is at http://localhost:3000/demo.

```bash
npm run lint
npm run typecheck
npm run build
```

## Vercel

- Root directory: `web/`
- Environment variable: `NEXT_PUBLIC_API_URL` = `https://spend-recon-api.fly.dev`
- Domain: `spend.hokheng.com`

Leave `NEXT_PUBLIC_DEMO_MOCK` and `NEXT_PUBLIC_DEV_API_KEY` unset. The session header is `Authorization` in `web/lib/api.ts` (`DEMO_SESSION_HEADER`). Change that one constant if the sandbox reads `X-Demo-Session` instead.
