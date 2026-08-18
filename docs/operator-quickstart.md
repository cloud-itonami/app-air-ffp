# operator-quickstart — app-air-ffp

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントは要らない（§5 の deploy だけが要る）。

出力はすべて 2026-08-18 に実際に walk した結果である。**飛ばした手順は
「合格した手順」ではない** —— §7 に、walk できなかったものと理由を書く。

## 0. 前提と、この repo 族の環境の罠

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| clojure | `clojure --version` | ビルド時のみ |

1. **remote は `origin` ではない。** west は remote を org 名で作るので
   `cloud-itonami`。`git fetch origin` はアクセス権エラーで落ち、`origin/main` は
   解決しない。repo が無いという意味ではない。
2. **`error: could not read IPC response` は fsmonitor daemon**であって、あなたの
   コマンドではない。それでも成功している。`-c core.fsmonitor=false` で黙る。
3. **npm 11.16 は `kotoba/` の git 依存を install できない**（§7）。
4. **`.gitignore` は在る**（移行で追加した）。ビルドしても `dist/` や
   `node_modules/` が untracked で散らからない。

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-air-ffp.git
cd app-air-ffp
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

実際の出力（末尾）:

```
SCANNED	25
PASS	tracked-files	expected=25	actual=25
...
PASS	adr-is-tx-data	expected=true	actual=true
OK	every claim in README.md and docs/operator-quickstart.md holds
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の
答えで、「検査して問題なし」と混ぜない。

この検査に入っている移行の不変条件:

- appview の TypeScript が戻っていないこと（撤去した 9 パスの不在 + 別名の `.ts`）
- **`kotoba/` が消えていないこと**（ファイル数 7 / `.ts` 数 5 を pin。
  これは「TypeScript を全部消す」という読みへの歯止めである）
- `wrangler.jsonc` の `main` が shadow の出力先を指していること
- ページが route 表と env から描かれていること
- **`:warnings-as-errors` が `:compiler-options` の下に在り
  `:build-options` の下に無いこと**（§4 の理由。EDN として読む。grep しない）

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'air-ffp.route-test)
(run-tests 'air-ffp.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing air-ffp.route-test

Ran 6 tests containing 30 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400（`/xrpc/a/b` は移行前の
rest parameter と同じく転送する。1 セグメントに絞るのは移行ではなく方針変更）、
MCP router の URL 解決、`result`/`structuredContent` の剥がし方、
**`APP_CAPABILITIES` を env から読むこと**、そして**ページが route 表から
描かれること**（固定値を焼いていたら落ちる）。

### 落ちることを確かめた（2026-08-18 実測）

| 壊したもの | 赤くなったもの |
|---|---|
| ページが route 表を無視して空の表を描く | route 3 件のセル assertion（3 failures） |
| capability を env でなく literal から出す | `declared-capabilities` の空入力ケース |
| 多段 XRPC を 1 セグメントに絞る | `/xrpc/a/b` の転送 assertion |

**外して数えなかった mutation もある。** 最初の「表を空にする」版では
`/health` と `/xrpc/:nsid` が赤くなったが **`/` は緑のまま**だった ——
素の `"/"` を HTML 全体から探す assertion は、あらゆる閉じタグに一致するので
**落ちようがない**。表のセル（`<span class="ffp-mono">/</span>`）を探す形に
直してから当て直し、3 件とも赤くなることを確認した。
**外した mutation の緑は実演ではない。**

### EDN が読めることの検査も、落ちようがなかった（直した）

検証器は ADR と `shadow-cljs.edn` を EDN として読む。当初これを
`cljs.reader/read-string` にそのまま渡していたが、**あれは最初の form だけを
読んで残りを捨てる**。実測:

```bash
printf '[{:a 1}]\n{:unbalanced "\n' > probe.edn
# (read-string t)               => [{:a 1}]  vector? true   ← 緑のまま
# (read-string (str "[" t "]")) => THREW: Unexpected EOF reading string
```

いまは全体を vector で包んで読み、**top-level form がちょうど 1 個**であることを
要求する。落として確かめた 3 通り:

| 壊し方 | 結果 |
|---|---|
| ADR の `]` の後ろに `{:unbalanced "` | `adr-is-tx-data` FAIL / exit 1 |
| ADR の後ろに**妥当な**第 2 form | `expected exactly 1 top-level form, got 2` / exit 1 |
| `shadow-cljs.edn` の後ろに第 2 form | UNDETERMINED / **exit 2** |

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/render.cljs <<'EOF'
(require '["node:fs" :as fs] '[air-ffp.view :as view] '[air-ffp.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs "/tmp/ffp-page.html"
    (view/render {:css css :routes route/routes
                  :vars [:APP_CAPABILITIES :APP_NANOID :APP_UI_TYPE :AGENTGATEWAY_MCP_ROUTER_URL]
                  :capabilities (route/declared-capabilities
                                  "[\"enrollMember\",\"accruePoints\",\"redeemReward\"]")
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" npx --yes nbb --classpath "$CP" /tmp/render.cljs

cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/ffp-page.html --min 95
```

実際の出力（末尾）:

```
  100.00  /tmp/ffp-page.html
aggregate: 100.00

axes scored: 10 (viewport, safe-area, dynamic-viewport, tap-targets, focus-visible,
                 reduced-motion, overflow-guard, color-scheme, responsive, semantics)
NOT scored: input-zoom, contrast — pass --extra-axes to include the optional ones
A pass says nothing about an axis that was not applied.
gate: aggregate 100.00 >= min 95.00 -> PASS
```

`--extra-axes` を足すと 12 軸すべてが当たり、それでも **100.00 / PASS**。

### この 100.00 が保証しないこと（実測）

**同じページから CSS を完全に外しても 96.63 で `--min 95` を PASS する。**

```
gate: aggregate 96.63 >= min 95.00 -> PASS
findings: overflow-guard headroom=0.03 / focus-visible headroom=0.00
```

つまり design-quality は「デザインシステムが実際に入っているか」を見ていない。
それを見るのは §4.5 の smoke の 2 本目だけである。

## 4. bundle をビルドする

**高負荷ビルドは workspace 全体で同時 1 本に制限されている**（superproject
`CLAUDE.md` の resource governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

**exit 2 は失敗ではなく順番待ち**（`resource-guard: build is already running
(pid=…)`）。迂回しない。この walk では 1 回のビルドが 10 回まで待たされた。

実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 22.96s)
-rw-r--r--  1 junkawasaki  wheel  246654  dist/worker.js
sha256 da6ce3de5ba197850bb2ce1e483977f0874a24ef08cc6618ea0fd4883334e332
```

### 「ビルドが通った」は検査ではなかった —— 両方の置き場所で測った

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` がある。
これが無い（または置き場所を間違えている）と、shadow は存在しない var を
**WARNING** として扱い **exit 0** して、最初のリクエストで落ちる bundle を
書き出す。

`src/air_ffp/worker.cljs:112` の `route/dispatch` を存在しない
`route/dispatch-nonexistent` に改名し、**置き場所を変えて 2 回**測った:

| `:warnings-as-errors` の位置 | exit | `dist/worker.js` |
|---|---|---|
| `:compiler-options`（現在） | **1** | **書かれない** — sha `da6ce3de…` / 246,654 B のまま不変 |
| `:build-options`（間違い） | **0** | **新しく出荷された** — `8a4c645b…` / 245,755 B、`1 warnings` |

`:compiler-options` 版のエラー:

```
Use of undeclared Var air-ffp.route/dispatch-nonexistent
{:warning :undeclared-var, :line 112, :column 45,
 :shadow.build.compiler/warning-as-error true}
```

`:build-options` 版が出荷した bundle を実際に叩くと:

```
UNDETERMINED	could not exercise the bundle: Cannot read properties of undefined (reading 'h')
```

**置き場所を間違えた `:warnings-as-errors` は、それ自身が「落ちようのない検査」
である。** shadow が読むのは `[:compiler-options :warnings-as-errors]` だけで、
もう一方は黙って無視される。だから検証器はこれを **EDN として読む** ——
`shadow-cljs.edn` のコメントにも検証器の docstring にも `:build-options` という
文字列が入っているので、grep は自分の散文に引っかかる。

改名を戻して再ビルドすると sha は `da6ce3de…` に戻った（確認済み）。

## 4.5 ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

実際の出力（22 項目、末尾）:

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
PASS	page advertises /xrpc/:nsid as a table cell	expected=true	actual=true
PASS	page hides other var values	expected=false	actual=false
PASS	page shows the relay target it uses	expected=true	actual=true
PASS	page shows capabilities READ FROM env, not baked	expected=true	actual=true
PASS	page uses the design system components	expected=true	actual=true
PASS	page carries the stylesheet itself	expected=true	actual=true
PASS	/_app/meta was not carried over	expected=404	actual=404
PASS	multi-segment xrpc is relayed exactly like a single segment	expected=true	actual=true
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない。確認済み:
`UNDETERMINED	no bundle at …` / `Refusing to report a pass`）。

### デザインシステムの検査を 2 本に割った理由 —— 片方は落ちなかった

`dads-table` が在ることだけを見る形は落ちない検査だった。それは view が出力する
markup であって、CSS が 1 バイトも入っていないページにも現れる。実測:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない）|
| `--color-primitive-blue` | 45 | **0** |

`src/air_ffp/worker.cljs` の `(rc/inline "jp_go_dds/dds.css")` を `""` に替えて
**再ビルド**（`746916bc…` / 171,315 B）し、smoke を回した:

```
PASS	page uses the design system components	expected=true	actual=true
FAIL	page carries the stylesheet itself	expected=true	actual=false
FAILED	1 check(s): page carries the stylesheet itself
```

**後者だけが赤い。** 2 本は別の主張である ——「view がライブラリを呼んだ」と
「stylesheet が実際に bundle に入った」。

### 値の露出は印を 2 種類（隠す / 出す）で見る

片方だけだと「全部隠す」実装も「全部出す」実装も通る。smoke は
`APP_UI_TYPE` の値に**出てはいけない印**を、`AGENTGATEWAY_MCP_ROUTER_URL`
（`.invalid` なので実 DNS に依存しない）と `APP_CAPABILITIES` に
**出なければいけない値**を置いて、両方向を見る。

実在しそうな値（`"yoro"` 等）は使わない —— 他の文言と偶然一致しうるし、引用符
ごと探すと renderer が `"` を `&quot;` に escape するので**決して一致しない**
（＝落ちようがない検査になる）。

## 4.6 Workers ランタイム（workerd）で動かす

Node で import する smoke より強い検査。実際の workerd で起こす。

```bash
cd "$REPO"
npx --yes wrangler@latest dev --local --port 8807 --ip 127.0.0.1
# 別シェルで
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://127.0.0.1:8807/
curl -s http://127.0.0.1:8807/health
curl -s -o /dev/null -w '%{http_code}\n' -X POST    http://127.0.0.1:8807/xrpc/
curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS http://127.0.0.1:8807/xrpc/x
curl -s -o /dev/null -w '%{http_code}\n'            http://127.0.0.1:8807/nope
curl -s -o /dev/null -w '%{http_code}\n' -X POST    http://127.0.0.1:8807/health
curl -s -X POST -H 'content-type: application/json' -d '{}' \
     http://127.0.0.1:8807/xrpc/com.etzhayyim.apps.airFfp.enrollMember
```

実際の出力:

```
200 text/html; charset=utf-8
{"ok":true,"app":"air-ffp","runtime":"cljs","routes":["/","/health","/xrpc/:nsid"]}
400
204
404
405
{"error":"MCP router unreachable","detail":"internal error; reference = q04cpr0352ismnu7sbim68n9",
 "url":"https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}   [502]
```

返ってきた HTML の中身（実測）: `class="dads-table"` × 1、
`--color-primitive-blue` × 45、`enrollMember` × 1（＝実 env の
`APP_CAPABILITIES` から読めている）、`a1rffp01` × **0**（＝`APP_NANOID` の
**値**は出していない）。

`compatibility_flags`（`nodejs_compat` / `nodejs_als`）は SvelteKit の
adapter-cloudflare 由来で、この bundle には要らない。**撤去は憶測ではなく
この実測で確かめてから行った。**

`wrangler dev` は継承した `rules` について 1 つ warning を出す:
`The module rule {"type":"CompiledWasm",...} does not have a fallback`。
tree に `.wasm` は 1 つも無いので inert。移行では触っていない。

## 5. deploy

```bash
cd "$REPO" && npx wrangler deploy
```

**この walk では deploy していない。** そして route が指すホストは解決しない:
`air-ffp.etzhayyim.com` / `a1rffp01.etzhayyim.com` とも `dig +short` が空。
deploy が成功しても誰も到達できない。`/xrpc/` の中継先 `mcp.etzhayyim.com` も
同様なので、到達できたとしても中継は **502 を返す**（成功と同じ形で隠さない）。

superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
許さない点も併せて注意。

## 6. 5 つの宣言が 3 通りの operation 数を言っている（移行では直っていない）

| source | says | deployed? |
|---|---|---|
| `wrangler.jsonc` `APP_CAPABILITIES` | **3** — enrollMember / accruePoints / redeemReward | yes、runtime var として |
| `wrangler.jsonc` `APP_DESCRIPTION` | **8**（散文） | yes、runtime var として |
| `kotoba/src/registry.ts` | 301 行の validation code 群 | no |
| `kotodama.jsonld` `capabilities` | **3** | 記述子として |
| 移行前の `src/app.ts` | **8**（名指し） | **no**（どの bundle にも無かった） |

ページが出すのは **`APP_CAPABILITIES` だけ**で、しかも env から読む
（焼いていない）。どれが正しいかは主張しない。統一は別の決定。

## 7. NOT WALKED — `kotoba/` のテスト

`kotoba/` は 129 行に 8 tests を持ち、301 行の registry を検査する。ここで最も
価値のある未実行の検査だが、**この機械では install できない**:

```bash
cd kotoba && npm install
#   npm error code EALLOWSCRIPTS
#   npm error --allow-scripts is not allowed in project-scoped installs.
```

依存は 2 つとも git URL（`@etzhayyim/sdk` / `@etzhayyim/sdk-mock`）で、その
preparation が nested install を走らせ、npm 11.16 が拒否する。
**この移行はそれを直していない。この文書はこのスイートが通るとは言わない。**

ただし**依存が実在することは確かめた**（README「`kotoba/` は残す」）:

```bash
git fetch https://github.com/etzhayyim/com-etzhayyim-sdk.git 12314a0c…   # OK
git cat-file -t 12314a0c…                                                # commit
```

**GitHub API ではなく git に訊く。** API は実在する commit に 404 を返すことが
ある（今回は両方 API も一致したが、判断の根拠にしたのは git の方）。

## 8. ここに無いもの

- **dispatcher 経路 / `/_app/meta`** —— 移行前の `src/app.ts` にあり、どこにも
  deploy されていなかった経路。宛先が NXDOMAIN で、かつ `DISPATCHER_URL` /
  `DISPATCHER_INTERNAL_SECRET` の binding が `wrangler.jsonc` に無いので
  **持ち越していない**（README の「持ち越さなかったもの」）
- **会員登録・マイル加算・特典交換の実装そのもの** —— MCP router の向こう側
- **`MIGRATION-TODO.md` の 7 項目の憲章適合レビュー**
