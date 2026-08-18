# app-air-ffp

**航空会社のマイレージ会員プログラム（frequent flyer program）の appview。**
会員登録・マイル加算・特典交換といった処理そのものはここには無い —— この repo が
持つのは**公開面（appview）**で、XRPC を AgentGateway の MCP router へ中継する。

`etzhayyim/root` の `60-apps/etzhayyim-project-air-ffp` からの抽出物で、
**2026-08-18 に appview を TypeScript/Svelte から ClojureScript へ移行した**
（`docs/adr/0001`）。数字はすべて `scripts/verify-docs-claims.cljs` が tree から
再計算して検査する。

## deploy されるものは、いま読んでいるソースである

```
src/air_ffp/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/air_ffp/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/air_ffp/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js            ← wrangler.jsonc の "main" が指すもの
```

移行前の `main` は **`svelte/.svelte-kit/cloudflare/_worker.js`** ——
**この tree に存在しないパス**を指していた（`git ls-files | grep -c svelte-kit`
は 0、ディスク上にも無い）。一方 `src/app.ts`（76 行、読み手が最初に開くファイル）
はどこからも import されず、どの tsconfig の `include` にも入っていなかった。
いまは `main` が指す bundle が上のソースからコンパイルされたものなので、その形は
構造的に起こり得ない。`scripts/verify-docs-claims.cljs` が
**shadow の出力先と wrangler の `main` と export の ns 名の 3 つが噛み合って
いること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからで
ある（入口を当面 cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `air-ffp.route/routes` で、ページもそこから描く。** 移行前の
ページは `routeCount: 0` / `routes: []` / `vars: []` を literal で持っており、
隣の `wrangler.jsonc` が route 2 パターン・var 8 個を宣言していることに
気づけなかった（`relativePath` も抽出前の monorepo のパスのままだった）。いまは
route 表を渡す側が持ち、ページは描くだけなので、両者がずれる余地が無い。

`/health` は移行前 `src/app.ts` に在ったが**どこにも deploy されていなかった**。
今回それを実際に deploy される面へ持ち上げたので、**これは移植ではなく追加である。**

## `kotoba/` は残す —— これは移行ではなく破壊になるところだった

この repo には appview とは別に **`kotoba/`（TypeScript のドメインライブラリ、
7 ファイル・33,676 バイト、うち `.ts` が 5 本）** が在る。「TypeScript を全部
消す」と読めば消える位置に在るが、測った結果は:

| 問い | 測り方 | 結果 |
|---|---|---|
| どれかの bundle に入っているか | `git grep kotoba -- svelte src wrangler.jsonc package.json` | **0 件** |
| この移行が置き換えるものから参照されているか | 同上 | **無し** |
| 依存が解決するか | `git fetch <url> <sha>` → `git cat-file -t` | **両方 `type=commit`** |

`@etzhayyim/sdk` (`12314a0c…`) と `@etzhayyim/sdk-mock` (`c857ff9b…`) はどちらも
git URL 固定 SHA。**GitHub API ではなく git に訊いた**（API は実在する commit に
404 を返すことがある。今回は両方 API も一致した）。

したがって dead ではなく、この移行の対象でもない。**残した。** 検証器は
`kotoba/` の**ファイル数 7 と `.ts` 数 5 を pin** しており、黙って増えることも
減ることもできない。移行するなら、それが依存する `@etzhayyim/sdk` に cljs の面を
用意する別の決定が要る。

なお `kotoba/` のテストは**この機械では走らない** —— npm 11.16 が git 依存の
nested install を `EALLOWSCRIPTS` で拒否する（旧 quickstart §6 が記録済み。
今回の移行はこれを直していない）。

## いま在るもの — 25 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/air_ffp/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/air_ffp/route_test.cljc`（6 tests / 30 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| 検査 | `scripts/{smoke-worker.cljs, verify-docs-claims.cljs}` |
| Worker 設定 | `wrangler.jsonc` |
| ドメインライブラリ（移行対象外） | `kotoba/`（7 ファイル） |
| actor 記述子 | `kotodama.jsonld` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` / `MIGRATION-TODO.md` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/0001-*.edn` |

**appview の TypeScript は 0 本、正本言語（`.cljs`/`.cljc`）が 4 本。**
移行前は 3 対 0 だった（`src/app.ts` / `svelte/…/+server.ts` /
`svelte/vite.config.ts`）。`kotoba/` の 5 本は**別勘定**で pin してある ——
1 つの「.ts 総数」に混ぜると、`kotoba/` を消すことが改善に見えてしまう。

### 撤去したもの（9 パス）

```
src/app.ts                                    package.json
svelte/package.json                           svelte/src/app.html
svelte/src/routes/+page.svelte                svelte/svelte.config.js
svelte/src/routes/xrpc/[...path]/+server.ts   svelte/tsconfig.json
svelte/vite.config.ts
```

root の `package.json` は `tsc --noEmit` を 1 つ持つだけで、root に tsconfig.json
は無く、唯一の対象だった `src/app.ts` も撤去したので一緒に落とした。
検証器はこの 9 パスの**不在を名指しで**検査し、加えて `.svelte` / `svelte.config` /
`svelte/` が**別名で**戻ってきても捕まえる。

## ページが出す値・出さない値

env の**キー名**は出す。**値は出さない —— ただし 2 つだけ例外**:

- `AGENTGATEWAY_MCP_ROUTER_URL`（どこへ中継するかは運用者が外から見る必要がある）
- `APP_CAPABILITIES`（公開されている capability 宣言そのもの）

smoke はこれを**3 つの独立した印**で見る: 表示されない var に置いた印が
**出ていないこと**、中継先の値が**出ていること**、capability の値が
**出ていること**。片方向だけだと「全部隠す」実装も「全部出す」実装も通ってしまう。
capability の印が効くのはもう 1 つ理由があって、**それが出る = 一覧をページに
焼いていない**という主張にもなる。

## デザインシステムの検査は 2 本ある

`dads-table` が在ることを 1 本で見る形は**落ちない検査**だった —— それは view が
出力する markup であって、CSS が 1 バイトも入っていないページにも現れる。
実測（このページ、2026-08-18）:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない）|
| `--color-primitive-blue` | 45 | **0** |

だから 2 本に割った。**component を使ったか**（`class="dads-table"`）と、
**stylesheet が実際に入ったか**（`--color-primitive-blue`）は別の主張である。
`(rc/inline "jp_go_dds/dds.css")` を `""` にして**再ビルド**すると、smoke は
後者**だけ**を赤にする（確認済み）。

**design-quality の 100.00 はこの区別をしない。** 実測: この同じページから CSS を
完全に外しても **96.63 で `--min 95` を PASS** する。CLI 自身が
「axes scored: 10 … NOT scored: input-zoom, contrast」と出力するので、その行を
読むこと。`--extra-axes` で 12 軸すべて当てても本物のページは 100.00 だった。
「デザインシステムが実際に入っている」と言えるのは smoke の 2 本目だけである。

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。
色・寸法は `--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも
置かない。app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり
`shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95、
`--extra-axes` の 12 軸でも 100.00）**。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | `dig +short` |
|---|---|---|
| `air-ffp.etzhayyim.com` | 公開ホスト（wrangler の route） | **空（NXDOMAIN）** |
| `a1rffp01.etzhayyim.com` | 同（nanoid 側） | **空（NXDOMAIN）** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **空（NXDOMAIN）** |

deploy 先も中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す**
——成功と同じ形で隠さない。実 workerd で確認済み（quickstart §4.6）。

## 持ち越さなかったもの（黙って消していない）

移行前の `src/app.ts` にあって**どこにも deploy されていなかった**経路のうち、
次は**意図的に移していない**:

- **dispatcher 経路**（`/xrpc/com.etzhayyim.apps.airFfp.*` → `DISPATCHER_URL` へ
  `x-internal-secret` 付きで POST）。**2 つの理由で dead** —— 既定の宛先
  `dispatcher.etzhayyim.com` が NXDOMAIN で、かつ `DISPATCHER_URL` も
  `DISPATCHER_INTERNAL_SECRET` も `wrangler.jsonc` の vars に**宣言が無い**。
- **`/_app/meta`** —— `/health` と同じ body を返す別名。`/health` だけ残した。

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。

## 多段 XRPC パスは移行前と同じく転送する

移行前の SvelteKit route は rest parameter `[...path]` で受けており、
**空文字だけを 400** にして `a/b` はそのまま tool 名として転送していた。
1 セグメントに絞るのは**移行ではなく方針変更**なので、ここではしていない。
絞るなら別の決定として記録する。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `cd21bc6f` と宣言し、
`:allowed-additions` に `README.edn` と `migration.edn` を持つ。移行後の状態:

- 継承した 5 ファイル（4,290 バイト）は**いまも 1 バイトも変わっていない**
  （sha256 を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、存在しない
  SvelteKit client を指す `assets` の撤去、`compatibility_flags` の撤去、
  `APP_FRAMEWORK` の更新）
- `docs/operator-quickstart.md` は移行前の tree を説明していたので書き直した

`rules`（`CompiledWasm` / `**/*.wasm`）は**継承のまま残した** —— Svelte 残骸では
なく汎用の wrangler module rule で、tree に `.wasm` は 1 つも無いので inert。
ただし `wrangler dev` は
`The module rule … does not have a fallback` と warning を出す（実測）。
撤去する理由をこの移行では測っていないので触っていない。

## 残っている欠陥（移行では直っていない）

1. **ホストが 3 つとも NXDOMAIN**（上記）。deploy するか retire するかは別の決定。
2. **`MIGRATION-TODO.md` のチェックボックス 7 件が未チェック**のまま。憲章適合の
   手動レビューは未実施であると文書自身が書いている。
3. **`kotoba/` のテスト 8 本が走らない**（npm の `EALLOWSCRIPTS`）。
4. **operation 数の宣言がいまも食い違っている** —— `APP_CAPABILITIES` は 3、
   `APP_DESCRIPTION` の散文は 8、`kotoba/src/registry.ts` はさらに別。ページが
   出すのは**実行時に consumer が読む唯一の値**（`APP_CAPABILITIES`）だけで、
   どれが正しいかは主張しない。統一は別の決定。

## 検証

```bash
nbb scripts/verify-docs-claims.cljs .          # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。

検証器が EDN（ADR・`shadow-cljs.edn`）を読むときは、**全体を vector で包んでから
読み、top-level form がちょうど 1 個**であることを要求する。
`cljs.reader/read-string` をそのまま使うと**最初の form だけ読んで残りを捨てる**
ので、ファイル末尾に何を足しても緑のままになる —— それ自体が落ちようのない
検査だった（実測と 3 通りの mutation は quickstart §2）。
テスト・ビルド・smoke は `docs/operator-quickstart.md`。
