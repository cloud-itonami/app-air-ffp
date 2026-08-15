# Operator quickstart — app-air-ffp

22 tracked files. The one thing to know before reading any of them: **the file that
looks like the entry point is not the one that gets deployed**, and the two disagree
about what this app does.

Steps marked ✅ were run against this tree on 2026-08-16. §6 lists what could not be
walked and why — a step that was skipped is not a step that passed.

---

## 1. ✅ What wrangler deploys, proven by building it

```bash
grep '"main"' wrangler.jsonc
#   "main": "svelte/.svelte-kit/cloudflare/_worker.js"
```

`main` is the **SvelteKit build output**, not `src/app.ts`. So build it and look:

```bash
cd svelte && npm install --no-audit --no-fund && npm run build   # ✓ built in 1.99s
grep -rl 'health' .svelte-kit
#   (nothing)
```

`health` appears **nowhere** in the entire build output — and that is the whole
deployable unit, not one file: `_worker.js` is 4.3 KB and imports
`../output/server/index.js`, and the xrpc handler compiles to
`.svelte-kit/output/server/entries/endpoints/xrpc/_...path_/_server.ts.js`.

Meanwhile `src/app.ts` — 76 lines, the obvious place to look — opens with

```javascript
// air-ffp.etzhayyim.com — airline frequent flyer program layer
// Thin-edge dispatcher: business logic in AgentGateway MCP + pod-side LangServer.
// 8 methods: enrollMember / accruePoints / redeemReward / updateTier / …
```

and serves `/health` and `/_app/meta`. **Neither endpoint is reachable on the
deployed worker.** `wrangler.jsonc` sets `not_found_handling: "none"`, so a GET
`/health` is a 404, and any monitor pointed at it is watching a path that does not
exist. This is the migrated-appview facade pattern: the deployed handler is not the
file a reader opens.

The deployed worker has exactly two routes:

```bash
find svelte/src/routes -type f | sed 's|svelte/src/routes||'
#   /+page.svelte
#   /xrpc/[...path]/+server.ts
```

## 2. ✅ Five declarations, three different operation counts

| source | says | deployed? |
|---|---|---|
| `wrangler.jsonc` `APP_CAPABILITIES` | **3** — enrollMember, accruePoints, redeemReward | yes, as a runtime var |
| `wrangler.jsonc` `APP_DESCRIPTION` | **8** in prose | yes, as a runtime var |
| `src/app.ts` comment + dispatcher | **8** named methods | **no** |
| `kotoba/src/registry.ts` | 301 lines of validation codes (`memberNotFound`, `invalidQualifyingMiles`, `invalidPricePerMile`, …) | no |
| `svelte/…/xrpc/[...path]/+server.ts` | **0** — see §3 | **yes** |

`APP_CAPABILITIES` is the one a consumer reads at runtime, and it lists three of the
eight. Nothing here says which count is authoritative, so treat all five as claims
and the deployed pair as the only ones with effect.

## 3. ✅ The deployed handler names no operation at all

The xrpc route is one dense line of code. What it does:

- takes whatever NSID is in the path,
- forwards it to `AGENTGATEWAY_MCP_ROUTER_URL` (default
  `https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message`) as a JSON-RPC
  `tools/call` with `name` = the NSID,
- unwraps `result.structuredContent` and returns it `cache-control: no-store`.

```bash
grep -c 'airFfp' svelte/src/routes/xrpc/'[...path]'/+server.ts   # 0
```

So it serves **every** operation and **none**: the method list lives upstream in the
MCP router, and this worker is a generic pass-through that would forward
`com.etzhayyim.apps.anythingElse.doThing` just as willingly. That is a deliberate
BFF design (`x-etzhayyim-bff: sveltekit-edge-bff` is set on every forwarded request),
but it means **this repository is not where you learn what the app can do**, and
`APP_CAPABILITIES` is documentation rather than enforcement.

## 4. ⚠ The landing page describes an app with no routes and no vars

`svelte/src/routes/+page.svelte` embeds a generated summary object:

```javascript
{ "title": "Ai etzhayyim Project Air Ffp", "routeCount": 0, "routes": [], "vars": [],
  "xrpc": true, "relativePath": "60-apps/etzhayyim-project-air-ffp/svelte/src/routes/+page.svelte" }
```

`wrangler.jsonc` declares **two** route patterns (`a1rffp01.etzhayyim.com/*` and
`air-ffp.etzhayyim.com/*`) and **eight** upper-case vars. The summary was generated
before those existed, or from a source that did not have them, and it still ships —
its `relativePath` also still points into the monorepo this repository was extracted
from. Nothing breaks; the page just describes something else.

## 5. ⚠ The repository declares itself an unremediated seed

`MIGRATION-TODO.md`, 45 lines:

> **Status**: 🔄 TRANSFORM — seed copied 2026-05-21, codemod pending.

```bash
grep -c '^- \[ \]' MIGRATION-TODO.md   # 7 unchecked
grep -c '^- \[x\]' MIGRATION-TODO.md   # 0 checked
```

Seven constitutional invariants are listed as "likely violated and MUST be remediated
before this app can be considered etzhayyim-aligned" — replacing direct
`@atproto/api` / `viem` / IPFS-client imports with `@etzhayyim/sdk`, stripping
centralized DB code, stripping fiat processors, and a §2(a) military-use exclusion
codemod. None is ticked.

**Read the file's own last paragraph before treating those as findings**, because it
qualifies them:

> The TRANSFORM classification was based on the app's domain pattern (commerce /
> communication adapter / media etc.), not on detected violations. Manual review is
> still required to confirm Charter §2(a)-(h) and substrate-boundary compliance.

So the seven boxes are a review checklist derived from the app's category, not seven
confirmed defects. What is certain is that the review has not happened, and that
`wrangler.jsonc` meanwhile declares live routes on `etzhayyim.com`.

## 6. ⚠ NOT WALKED: the kotoba test suite

`kotoba/` has 8 tests in 129 lines against a 301-line registry — the most valuable
unrun check here. It does not install on this machine:

```bash
cd kotoba && npm install
#   npm error code EALLOWSCRIPTS
#   npm error --allow-scripts is not allowed in project-scoped installs.
```

Both dependencies are git URLs (`@etzhayyim/sdk`, `@etzhayyim/sdk-mock`) whose
preparation runs a nested install that npm 11.16 refuses. Adding an `allowScripts`
field does not help — the rejection happens inside the nested install. The sibling
`cloud-itonami/app-air-crew`'s quickstart §5 documents a workaround and §8 records
what it costs; this document does not claim the suite passes.

`svelte/` installs and builds cleanly (§1) because it has no git dependencies. Its
install does print `npm warn allow-scripts … pending`; the build succeeds anyway.

## 7. The family's environment traps

This repository is one of an `app-*` family with identical shape, and the traps are
shared. `cloud-itonami/app-air-crew/docs/operator-quickstart.md` §0 documents all of
them at length; the short list, so you do not have to open it first:

1. **the remote is not `origin`** — west names remotes after the org, so it is
   `cloud-itonami`. `git fetch origin` fails with an access-rights error and
   `origin/main` does not resolve.
2. **`error: could not read IPC response` on stderr is the fsmonitor daemon**, not
   your command. It still succeeded. `-c core.fsmonitor=false` silences it.
3. **npm 11.16 cannot install the `kotoba/` git dependencies** (§6).
4. **`esbuild --loader=ts` is rejected** for a file input; drop the flag.
5. **there is no `.gitignore`** — building in the checkout leaves `node_modules/` and
   `.svelte-kit/` untracked. Build in a worktree, or clean up after.

## 8. What the maturity instrument sees here ✅

```
· orgs/cloud-itonami/app-air-ffp  own=0.049  axis-docs=0bp → +2500bp
    ⚠ README が .md ではないので docs の README 成分は 0（README.edn 等が 1 件）
    ⚠ taxonomy に :repo/kind の行が無い → :default の重みで採点されている
```

Both warnings are about the instrument, not the repository. `README.edn` says
`:canonical-metadata :edn` — EDN is deliberately the canonical form here — while the
score reads `README.md`. And this repository has no row in
`manifest/repo-taxonomy.edn`, so it is scored against a guessed weight profile; its
`own` is not comparable to a repository whose kind is known. Recorded in
ADR-2608052000 and in the tick, not gaps to fill by adding a second README.
