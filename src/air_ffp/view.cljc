(ns air-ffp.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`(デジタル庁デザインシステム) —— superproject の
  skill `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン
  契約で書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。**
  これは装飾の都合ではなく、docs/adr/0001 が記録した欠陥そのものへの答えで
  ある —— 移行前のページは `routeCount: 0` と `vars: []` と
  `relativePath: \"60-apps/…\"`（抽出前の monorepo のパス）を literal で持って
  いて、隣の wrangler.jsonc が route 2・var 8 を宣言していることにも、この
  repo が既に抽出済みであることにも気づけなかった。ここでは route 表と設定を
  渡す側が持ち、ページは描くだけなので、両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う(bridge が DADS の上に再定義する)。
  DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれている 71 個の中だけ。"
  (str/join
   "\n"
   [".ffp-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".ffp-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".ffp-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "ffp-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes       air-ffp.route/routes（この Worker が実際に答えるもの）
   :vars         wrangler が渡した env のキー（**キー名だけ**。値は出さない）
   :capabilities APP_CAPABILITIES を解いたもの（**値を出す**。公開宣言なので）
   :mcp-url      XRPC の中継先（route/mcp-router-url の戻り値。**値を出す**）
   :built-at     bundle のビルド時刻（不明なら nil）"
  [{:keys [routes vars capabilities mcp-url built-at]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "Air FFP — Frequent Flyer Program")
    [:p {:class "ffp-lede"}
     "航空会社のマイレージ会員プログラムの appview 公開面。会員登録・マイル"
     "加算・特典交換といった処理そのものはここには無く、この面は XRPC を "
     "MCP router へ中継する。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "ffp-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "宣言されている capability"}
    (if (seq capabilities)
      [:div
       (into [:p] (interpose " " (map (fn [c] (dds/chip-label c)) capabilities)))
       [:p {:class "ffp-note"}
        "出所は env の "
        [:span {:class "ffp-mono"} "APP_CAPABILITIES"]
        " —— 実行時に consumer が読む唯一の宣言である。**ページに焼いていない**"
        "ので、設定を変えればここも変わる。"
        "なおこの repo には operation 数の宣言が複数あって食い違っている"
        "（docs/operator-quickstart.md）。ここが出すのは実行時の値だけで、"
        "どれが正しいかは主張しない。"]]
      [:p {:class "ffp-note"}
       "APP_CAPABILITIES が渡されていない（ローカル描画、または未設定）。"]))

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div
       (into [:p] (interpose " " (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "ffp-note"}
        "**キー名のみ。値は出さない** —— ただし上の capability と下の中継先は"
        "例外で、値そのものを出している。どちらも公開宣言であり、運用者が"
        "外から見る必要があるので意図的に表示する。それ以外の値は出さない。"]]
      [:p {:class "ffp-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "ffp-note"} "XRPC の中継先: "
     [:span {:class "ffp-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "ffp-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。"]
    (when built-at
      [:p {:class "ffp-note"} "bundle build: " built-at]))))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す(ライブラリは I/O を持たない)。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "Air FFP — Frequent Flyer Program"
    :description "航空会社のマイレージ会員プログラム appview の公開面。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
