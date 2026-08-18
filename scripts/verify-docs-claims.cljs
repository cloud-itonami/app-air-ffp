#!/usr/bin/env nbb
;; verify-docs-claims — re-derive every number README.md and docs/operator-quickstart.md
;; state, from the tree itself, and fail when the tree and the prose disagree.
;;
;; Before the cljs migration this file's load-bearing claim would have been a GAP:
;; wrangler.jsonc's `main` pointed at `svelte/.svelte-kit/cloudflare/_worker.js` --
;; a path with no file behind it in this tree -- while `src/app.ts`, the file that
;; read like the application, was imported by nothing and in no tsconfig include.
;; That gap is closed, so the claims assert the CLOSURE, and they are written so the
;; gap cannot quietly come back: the appview TypeScript is asserted ABSENT BY NAME,
;; not merely absent from a byte total.
;;
;; It also PINS what the migration deliberately did NOT touch. `kotoba/` is a
;; TypeScript domain library that is in no bundle, imported by nothing this migration
;; replaced, and whose two git dependencies resolve (measured with `git fetch <url>
;; <sha>` -> type=commit, not with the GitHub API). Deleting it on an "all TypeScript"
;; reading would have been destruction, not migration. So it stays -- and its file
;; count and .ts count are pinned here so it cannot grow silently either.
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> FIRST, default ".")
;; Exit:   0 every claim holds · 1 a claim is false · 2 could not answer

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[cljs.reader :as reader]
         '[clojure.string :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))

(def claims
  {:tracked-files 25
   :inherited-bytes 4290           ; the 5 inherited files still carried unchanged
   :svelte-artifacts 0             ; no .svelte / svelte.config / svelte-dir file survives
   :sveltekit-compat-flags 0       ; nodejs_compat / nodejs_als were adapter-cloudflare's
   :appview-ts-files 0             ; the appview's TypeScript, gone
   :kotoba-files 7                 ; the domain library, deliberately untouched -- PINNED
   :kotoba-ts-files 5              ; ... and it may not grow silently
   :production-canonical-files 4
   :declared-vars 8
   :declared-routes 2
   :wrangler-main "dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "air-ffp.worker/handler"})

;; Inherited files this repository still carries BYTE-IDENTICAL.
;; wrangler.jsonc is NOT in this set: the migration changed it deliberately (main,
;; assets, compatibility_flags, APP_FRAMEWORK) and it is checked by CONTENT below --
;; which is the point of separating the two, so an intended change and a stray one
;; do not look alike.
;; docs/operator-quickstart.md is not in it either: it described the pre-migration
;; tree and was rewritten.
(def preserved
  {"MIGRATION-TODO.md" "24996ae87106d57f579043cb568d5f7bd04233648b27c79b4bb6307d4d30d43a"
   "NOTICE" "9d3bd5678f857c647a465987cd8538580215416648991fd9de47e6dc648544f0"
   "README.edn" "d4256d7ff2f5224295166b65a6d7f9f9063d6411bed242bb02082feb7c7daf52"
   "kotodama.jsonld" "7612db35923f366c0fc06220ede5d801925416cbd14973cf60f805000f6372a1"
   "migration.edn" "7c94017f248109f07c8ea38ff016abd867882895d9b08a81d69422fda17e7dbf"})

;; What the migration REMOVED, by name. A byte total cannot say "the TypeScript is
;; gone"; this can, and it fails if any of it comes back.
(def removed-by-migration
  ["src/app.ts"
   "package.json"
   "svelte/package.json"
   "svelte/src/app.html"
   "svelte/src/routes/+page.svelte"
   "svelte/src/routes/xrpc/[...path]/+server.ts"
   "svelte/svelte.config.js"
   "svelte/tsconfig.json"
   "svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn read-whole-edn
  "EDN ファイル全体を 1 個の form として読む。読めたら `[:ok form]`、
  読めなければ `[:bad <理由>]`。

  **`cljs.reader/read-string` を直接使わない。** あれは**最初の form だけを読んで
  残りを捨てる**ので、ファイル末尾に何を足しても緑のままになる ——
  つまり『この EDN は読める』という検査が**落ちようがない**。

  実測 2026-08-18（この repo で）: `[{:a 1}]` の後ろに `{:unbalanced \"` を足した
  ファイルに対し
    (read-string t)              => [{:a 1}]  ... 緑
    (read-string (str \"[\" t \"]\"))  => THREW: Unexpected EOF reading string
  全体を vector で包んでから読み、**form がちょうど 1 個**であることを要求する。"
  [text]
  (try
    (let [forms (reader/read-string (str "[" text "]"))]
      (cond
        (not (vector? forms)) [:bad "wrapper did not read as a vector"]
        (not= 1 (count forms)) [:bad (str "expected exactly 1 top-level form, got " (count forms))]
        :else [:ok (first forms)]))
    (catch :default e [:bad (.-message e)])))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; the appview's TypeScript is gone, by name
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; Svelte is gone and must not come back. The removed list names the eight files;
    ;; these catch a return under ANY name -- a new .svelte file, a svelte.config, a
    ;; svelte/ directory, or the compat flags only adapter-cloudflare needed.
    (check! :svelte-artifacts (:svelte-artifacts claims)
            (count (filter #(or (str/ends-with? % ".svelte")
                                (str/includes? % "svelte.config")
                                (str/includes? % "/svelte/")
                                (str/starts-with? % "svelte/"))
                           files)))

    ;; Language of the source, split THREE ways because they are three different
    ;; claims: the appview has no TypeScript left; the domain library still has
    ;; exactly the TypeScript it had; the appview is written in the canonical
    ;; language. Lumping them into one ".ts count" would let a deletion of kotoba/
    ;; read as an improvement.
    (let [prod (remove #(str/starts-with? % "scripts/") files)
          kotoba (filter #(str/starts-with? % "kotoba/") files)]
      (check! :appview-ts-files (:appview-ts-files claims)
              (count (filter #(and (str/ends-with? % ".ts")
                                   (not (str/starts-with? % "kotoba/")))
                             prod)))
      (check! :kotoba-files (:kotoba-files claims) (count kotoba))
      (check! :kotoba-ts-files (:kotoba-ts-files claims)
              (count (filter #(str/ends-with? % ".ts") kotoba)))
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; the deployed bundle is built from the source in this tree
    (let [w (some-> (slurp* "wrangler.jsonc") strip-jsonc)
          sh-text (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh-text))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)
              [sh-tag sh-val] (read-whole-edn sh-text)
              sh (if (= :ok sh-tag)
                   sh-val
                   (do (undet! (str "shadow-cljs.edn is not readable EDN: " sh-val)) nil))]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          ;; the old config served a SvelteKit client dir that never existed here
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :sveltekit-compat-flags (:sveltekit-compat-flags claims)
                  (count (filter #{"nodejs_compat" "nodejs_als"}
                                 (or (get j "compatibility_flags") []))))
          (when sh
            (check! :shadow-builds-that-main true
                    (and (= (:shadow-output-dir claims)
                            (get-in sh [:builds :worker :output-dir]))
                         (= (symbol (:shadow-export claims))
                            (get-in sh [:builds :worker :modules :worker :exports 'default]))
                         (str/includes? (str (get j "main"))
                                        (str (:shadow-output-dir claims) "/worker.js"))))
            ;; :warnings-as-errors must be under :compiler-options and NOT under
            ;; :build-options -- shadow reads [:compiler-options :warnings-as-errors]
            ;; and SILENTLY IGNORES the other placement.
            ;;
            ;; Measured in this repo on 2026-08-18 with the same broken var both ways:
            ;;   under :compiler-options -> exit 1, no bundle written (sha unchanged)
            ;;   under :build-options    -> exit 0, "1 warnings", NEW bundle shipped,
            ;;                              which then threw
            ;;                              "Cannot read properties of undefined" on
            ;;                              its first request.
            ;;
            ;; So this is read AS EDN, never grepped: the docstring above and the
            ;; comment in shadow-cljs.edn both contain the string ":build-options",
            ;; and a grep would be tripped by prose that explains the trap.
            (check! :warnings-as-errors-under-compiler-options true
                    (true? (get-in sh [:builds :worker :compiler-options :warnings-as-errors])))
            (check! :warnings-as-errors-not-under-build-options true
                    (nil? (get-in sh [:builds :worker :build-options :warnings-as-errors])))))))

    ;; The page renders the route TABLE and the env's capabilities rather than baked
    ;; literals -- the defect ADR-0001 recorded was `routeCount: 0` and `routes: []`
    ;; beside a config declaring two route patterns. Asserted structurally, and NOT
    ;; by forbidding a substring: a check that a docstring explaining the old defect
    ;; can fail is a check about prose, not about code.
    (let [v (slurp* "src/air_ffp/view.cljc")
          w (slurp* "src/air_ffp/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (do
          (check! :page-renders-route-table true
                  (and (str/includes? v "[{:keys [routes vars capabilities mcp-url built-at]}]")
                       (str/includes? v "(route-rows routes)")
                       (str/includes? w ":routes route/routes")))
          (check! :page-reads-capabilities-from-env true
                  (str/includes? w "(route/declared-capabilities (:APP_CAPABILITIES e))")))))

    ;; The ADR is EDN tx-data and actually reads.
    (let [adr "docs/adr/0001-migrate-the-appview-from-typescript-to-clojurescript.edn"
          t (slurp* adr)]
      (if (nil? t)
        (undet! (str adr " unreadable"))
        (let [[tag d] (read-whole-edn t)]
          ;; 読めたかどうかは check であって undetermined ではない -- ADR が壊れて
          ;; いるのは「答えられなかった」ではなく「主張が偽」である。
          (if (not= :ok tag)
            (check! :adr-is-tx-data true (str "unreadable: " d))
            (check! :adr-is-tx-data true
                    (and (vector? d)
                         (= 1 (count d))
                         (map? (first d))
                         (= "accepted" (:adr/status (first d)))
                         (= "app-air-ffp-0001" (:adr/id (first d)))
                         (string? (:adr/body (first d)))
                         (> (count (:adr/body (first d))) 2000)))))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
