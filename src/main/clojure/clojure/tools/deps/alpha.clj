;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.tools.deps.alpha.util.concurrent :as concurrent]
    [clojure.tools.deps.alpha.util.dir :as dir]
    [clojure.tools.deps.alpha.util.session :as session]
    [clojure.tools.deps.alpha.extensions :as ext]

    ;; Load extensions
    [clojure.tools.deps.alpha.extensions.maven]
    [clojure.tools.deps.alpha.extensions.local]
    [clojure.tools.deps.alpha.extensions.git]
    [clojure.tools.deps.alpha.extensions.deps]
    [clojure.tools.deps.alpha.extensions.pom])
  (:import
    [clojure.lang PersistentQueue]
    [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private merge-alias-rules
  {:deps merge
   :extra-deps merge
   :override-deps merge
   :default-deps merge
   :classpath-overrides merge
   :paths (comp vec distinct concat)
   :extra-paths (comp vec distinct concat)
   :jvm-opts (comp vec concat)
   :main-opts (comp last #(remove nil? %) vector)})

(defn- choose-rule [alias-key]
  (or (merge-alias-rules alias-key)
    (throw (ex-info (format "Unknown alias key: %s" alias-key) {:key alias-key}))))

(defn- merge-alias-maps
  "Like merge-with, but using custom per-alias-key merge function"
  [& ms]
  (reduce
    #(reduce
       (fn [m [k v]] (update m k (choose-rule k) v))
       %1 %2)
    {} ms))

(defn combine-aliases
  "Find, read, and combine alias maps identified by alias keywords from
  a deps edn map into a single args map."
  [edn-map alias-kws]
  (->> alias-kws
    (map #(get-in edn-map [:aliases %]))
    (apply merge-alias-maps)))

(defn resolve-path-ref
  "Recursively resolve path refs to a coll of paths. Path refs may be:
    string - a path
    keyword - a path alias or the special alias, :paths
    coll of the above"
  [path-ref {:keys [paths aliases] :as edn-map}]
  (let [alias-map (merge aliases {:paths paths})]
    (loop [acc []
           [fpath & rpaths] [path-ref]]
      (cond
        (nil? fpath) acc
        (string? fpath) (recur (conj acc fpath) rpaths)
        (keyword? fpath) (let [res (get alias-map fpath)]
                           (if (coll? res)
                             (recur acc (concat res rpaths))
                             (recur acc (conj res rpaths))))
        (coll? fpath) (recur acc (concat rpaths fpath))))))

(defn lib-location
  "Find the file path location of where a lib/coord would be located if procured
  without actually doing the procuring!"
  [lib coord deps-config]
  (ext/lib-location lib coord deps-config))

(defn- canonicalize-deps
  [deps config]
  (reduce
    (fn [m [lib coord]] (conj m (ext/canonicalize lib coord config)))
    [] deps))

;; exclusions tree

(defn- add-exclusion
  [exclusions path libs]
  (assoc exclusions path (set libs)))

(defn- excluded?
  [exclusions path lib]
  (let [lib-name (first (str/split (name lib) #"\$"))
        base-lib (symbol (namespace lib) lib-name)]
    (loop [search path]
      (when (seq search)
        (if (get-in exclusions [search base-lib])
          true
          (recur (pop search)))))))

;; version map

;; {lib {:versions {coord-id coord}     ;; all version coords
;;       :paths    {coord-id #{paths}}  ;; paths to coord-ids
;;       :select   coord-id             ;; current selection
;;       :top      true}                ;; if selection is top dep

(defn- parent-missing?
  [vmap path]
  (when (seq path)
    (let [parent-lib (last path)
          parent-path (vec (butlast path))
          {:keys [paths select]} (get vmap parent-lib)]
      (not (contains? (get paths select) parent-path)))))

(defn- include-coord?
  [vmap lib path exclusions]
  (cond
    ;; lib is a top dep and this is it => select
    (empty? path) {:include true, :reason :top}

    ;; lib is excluded in this path => omit
    (excluded? exclusions path lib)
    {:include false, :reason :excluded}

    ;; lib is a top dep and this isn't it => omit
    (get-in vmap [lib :top])
    {:include false, :reason :use-top}

    ;; lib's parent path is not included => omit
    (parent-missing? vmap path)
    {:include false, :reason :parent-omitted}

    ;; otherwise => choose newest version
    :else
    {:include true, :reason :choose-version}))

(defn- dominates?
  [lib new-coord old-coord config]
  (pos? (ext/compare-versions lib new-coord old-coord config)))

(defn- add-coord
  [vmap lib coord-id coord path action config]
  (let [vmap' (-> (or vmap {})
                (assoc-in [lib :versions coord-id] coord)
                (update-in [lib :paths]
                  (fn [coord-paths]
                    (merge-with into {coord-id #{path}} coord-paths))))]
    (if (= action :top)
      {:include true
       :reason :new-top-dep
       :vmap (update-in vmap' [lib] merge {:select coord-id :top true})}
      (let [select-id (get-in vmap' [lib :select])]
        (if (not select-id)
          {:include true
           :reason :new-dep
           :vmap (assoc-in vmap' [lib :select] coord-id)}
          (let [select-coord (get-in vmap' [lib :versions select-id])]
            (cond
              (= select-id coord-id)
              {:include false
               :reason :same-version
               :vmap vmap}

              (dominates? lib coord select-coord config)
              {:include true
               :reason :newer-version
               :vmap (assoc-in vmap' [lib :select] coord-id)}

              :else
              {:include false
               :reason :older-version
               :vmap vmap})))))))

;; expand-deps

(defn- trace+
  [trace? trace entry include reason]
  (when trace?
    (conj trace (merge entry {:include include :reason reason}))))

(defn- next-path
  [pendq q on-error]
  (let [[fchild & rchildren] pendq]
    (if fchild
      {:path fchild, :pendq rchildren, :q' q}
      (let [next-q (peek q)
            q' (pop q)]
        (if (map? next-q)
          (let [{:keys [pend-children ppath]} next-q
                result @pend-children]
            (when (instance? Throwable result)
              (on-error result))
            (next-path (map #(conj ppath %) result) q' on-error))
          {:path next-q, :q' q'})))))

(defn- expand-deps
  [deps default-deps override-deps config executor trace?]
  (loop [pendq nil
         q (into (PersistentQueue/EMPTY) (map vector deps))
         version-map nil
         exclusions nil
         trace []]
    (let [{:keys [path pendq q']} (next-path pendq q #(do
                                                        (concurrent/shutdown-on-error executor)
                                                        (throw ^Throwable %)))]
      (if path
        (let [[lib coord] (peek path)
              parents (pop path)
              override-coord (get override-deps lib)
              use-coord (cond override-coord override-coord
                              coord coord
                              :else (get default-deps lib))
              coord-id (ext/dep-id lib use-coord config)
              entry (cond-> {:path parents, :lib lib, :coord coord, :use-coord use-coord, :coord-id coord-id}
                      override-coord (assoc :override-coord override-coord))
              {:keys [include reason]} (include-coord? version-map lib parents exclusions)]
          (if include
            (let [use-path (conj parents lib)
                  {:deps/keys [manifest root] :as manifest-info} (ext/manifest-type lib use-coord config)
                  use-coord (merge use-coord manifest-info)
                  children-fut (dir/with-dir (if root (jio/file root) dir/*the-dir*)
                             (concurrent/submit-task executor
                               #(try
                                  (canonicalize-deps (ext/coord-deps lib use-coord manifest config) config)
                                  (catch Throwable t t))))
                  {:keys [include reason vmap]} (add-coord version-map lib coord-id use-coord parents reason config)]
              (if include
                (let [excl' (if-let [excl (:exclusions use-coord)]
                              (add-exclusion exclusions use-path excl)
                              exclusions)]
                  (recur pendq (conj q' {:pend-children children-fut, :ppath use-path}) vmap excl' (trace+ trace? trace entry include reason)))
                (recur pendq q' vmap exclusions (trace+ trace? trace entry include reason))))
            (recur pendq q' version-map exclusions (trace+ trace? trace entry include reason))))
        (cond-> version-map trace? (with-meta {:trace {:log trace, :vmap version-map, :exclusions exclusions}}))))))

(defn- lib-paths
  [version-map]
  (reduce
    (fn [ret [lib {:keys [select versions paths]}]]
      (let [coord (get versions select)
            paths (->> (get paths select) (map last) (remove nil?) vec)]
        (assoc ret lib (cond-> coord (seq paths) (assoc :dependents paths)))))
    {} version-map))

(defn- download-libs
  [executor lib-map config]
  (let [lib-futs (reduce-kv
                   (fn [fs lib coord]
                     (let [fut (concurrent/submit-task
                                 executor
                                 #(try
                                    (ext/coord-paths lib coord (:deps/manifest coord) config)
                                    (catch Throwable t t)))]
                       (assoc fs lib fut)))
                   {} lib-map)]
    (reduce-kv (fn [lm lib fut]
                 (let [result @fut]
                   (if (instance? Throwable result)
                     (do
                       (concurrent/shutdown-on-error executor)
                       (throw ^Throwable result))
                     (assoc-in lm [lib :paths] result))))
      lib-map lib-futs)))

(defn resolve-deps
  "Takes a deps configuration map and resolves the transitive dependency graph
  from the initial set of deps. args-map is a map with several keys (all
  optional) that can modify the results of the transitive expansion:

    :extra-deps - a map from lib to coord of deps to add to the main deps
    :override-deps - a map from lib to coord of coord to use instead of those in the graph
    :default-deps - a map from lib to coord of deps to use if no coord specified
    :trace - boolean. If true, the returned lib map will have metadata with :trace log
    :threads - long. If provided, sets the number of concurrent download threads

  Returns a lib map (map of lib to coordinate chosen)."
  {:arglists '([deps-map args-map])}
  ([deps-map args-map]
   (let [{:keys [extra-deps default-deps override-deps threads trace]} args-map
         n (or threads concurrent/processors)
         executor (concurrent/new-executor n)
         deps (merge (:deps deps-map) extra-deps)
         version-map (-> deps
                       (canonicalize-deps deps-map)
                       (expand-deps default-deps override-deps deps-map executor trace))
         lib-map (lib-paths version-map)
         lib-map' (download-libs executor lib-map deps-map)]
     (with-meta lib-map' (meta version-map))))
  ;; deprecated arity, retained for backwards compatibility
  ([deps-map args-map settings]
   (resolve-deps deps-map (merge args-map settings))))

(defn- make-tree
  [lib-map]
  (let [{roots false, nonroots true} (group-by #(-> % val :dependents boolean) lib-map)]
    (loop [q (into (PersistentQueue/EMPTY) roots)
           remaining nonroots
           tree {}]
      (let [[lib coord :as node] (peek q)
            q' (pop q)]
        (if node
          (let [{children true, not-used false} (group-by #(-> % val :dependents set (contains? lib)) remaining)]
            (recur (into q' children) not-used (assoc tree lib (assoc coord :children (map key children)))))
          tree)))))

(defn print-tree
  "Print lib-map tree to the console"
  [lib-map]
  (let [tree (make-tree lib-map)]
    (letfn [(print-node [lib indent]
              (let [{:keys [children] :as coord} (get tree lib)]
                (println (str indent (ext/coord-summary lib coord)))
                (doseq [child-lib children]
                  (print-node child-lib (str indent "  ")))))]
      (doseq [[lib coord] tree :when (-> coord :dependents nil?)]
        (print-node lib "")))))

(defn- chase-key
  [aliases key]
  (mapcat #(if (string? %) [% {:path-key key}] (chase-key aliases %))
    (get aliases key)))

(defn make-classpath-map
  "Takes a merged deps edn map and a lib map. Extracts the paths for each chosen
  lib coordinate, and assembles a classpath map. The classpath-args is a map with
  keys that can be used to modify the classpath building operation:

    :extra-paths - extra classpath paths to add to the classpath
    :classpath-overrides - a map of lib to path, where path is used instead of the coord's paths

  Returns the classpath as a vector of string paths."
  [{:keys [paths aliases] :as deps-edn-map} lib-map {:keys [classpath-overrides extra-paths] :as classpath-args}]
  (let [override-libs (merge-with (fn [coord path] (assoc coord :paths [path])) lib-map classpath-overrides)
        lib-paths (reduce-kv (fn [lp lib {:keys [paths]}]
                               (merge lp (zipmap paths (repeat {:lib-name lib}))))
                    {} override-libs)
        aliases' (assoc aliases :paths paths :extra-paths extra-paths)
        paths (mapcat #(chase-key aliases' %) [:paths :extra-paths])]
    (merge lib-paths (apply hash-map paths))))

(defn join-classpath
  "Takes a coll of string classpath roots and creates a platform sensitive classpath"
  [roots]
  (str/join File/pathSeparator roots))

(defn make-classpath
  "Takes a lib map, and a set of explicit paths. Extracts the paths for each chosen
  lib coordinate, and assembles a classpath string using the system path separator.
  The classpath-args is a map with keys that can be used to modify the classpath
  building operation:

    :extra-paths - extra classpath paths to add to the classpath
    :classpath-overrides - a map of lib to path, where path is used instead of the coord's paths

  Returns the classpath as a string."
  [lib-map paths classpath-args]
  (-> (make-classpath-map {:paths paths} lib-map classpath-args) keys join-classpath))

(defn tool
  "Transform project edn for tool by applying tool args (keys = :paths, :deps) and
  returning an updated project edn."
  [project-edn tool-args]
  (merge project-edn tool-args))

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
      nil vals)))

(defn merge-edns
  "Merge multiple deps edn maps from left to right into a single deps edn map."
  [deps-edn-maps]
  (apply merge-with merge-or-replace (remove nil? deps-edn-maps)))

(defn calc-basis
  "Calculates and returns the runtime basis from a master deps edn map, modifying
   resolve-deps and make-classpath args as needed.

    master-edn - a master deps edn map
    args - an optional map of arguments to constituent steps, keys:
      :resolve-args - map of args to resolve-deps, with possible keys:
        :extra-deps
        :override-deps
        :default-deps
        :threads - number of threads to use during deps resolution
        :trace - flag to record a trace log
      :classpath-args - map of args to make-classpath-map, with possible keys:
        :extra-paths
        :classpath-overrides

  Returns the runtime basis, which is the initial deps edn map plus these keys:
    :resolve-args - the resolve args passed in, if any
    :classpath-args - the classpath args passed in, if any
    :libs - lib map, per resolve-deps
    :classpath - classpath map per make-classpath-map"
  ([master-edn]
    (calc-basis master-edn nil))
  ([master-edn {:keys [resolve-args classpath-args]}]
   (session/with-session
     (let [libs (resolve-deps master-edn resolve-args)
           cp (make-classpath-map master-edn libs classpath-args)]
       (cond->
         (merge master-edn {:libs libs, :classpath cp})
         resolve-args (assoc :resolve-args resolve-args)
         classpath-args (assoc :classpath-args classpath-args))))))

(comment
  (require '[clojure.tools.deps.alpha.util.maven :as mvn])

  (def ex-svc (concurrent/new-executor 2))

  (calc-basis
    {:mvn/repos mvn/standard-repos
     :paths ["src" "test"]
     :deps {'org.clojure/clojure {:mvn/version "1.9.0"}
            'org.clojure/core.memoize {:mvn/version "0.5.8"}}
     :aliases {:x {:extra-paths ["a" "b"]}}}
    {:classpath-args {:extra-paths ["a" "b"]}})

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.apache.xmlgraphics/batik-transcoder {:mvn/version "1.7"}}
               nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}
                'org.clojure/core.memoize {:mvn/version "0.5.8"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}
                'org.clojure/clojurescript {:mvn/version "1.9.946"}
                'reagent/reagent {:mvn/version "0.6.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/core.async {:mvn/version "0.3.426"}
                'reagent/reagent {:mvn/version "0.6.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/tools.deps.alpha {:mvn/version "0.4.277"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (print-tree
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.8.0"}
                          'org.clojure/core.memoize {:mvn/version "0.5.8"}}
                   :mvn/repos mvn/standard-repos} nil))

  (make-classpath-map
    {:paths ["src"]}
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}
                          'org.clojure/core.memoize {:mvn/version "0.5.8"}}
                   :mvn/repos mvn/standard-repos} nil)
    {:extra-paths ["test"]})

  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/tools.analyzer.jvm {:mvn/version "0.6.9"}}
                   :mvn/repos mvn/standard-repos} nil))

  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire {:mvn/version "5.7.0"}}
                   :mvn/repos mvn/standard-repos} nil))

  ;; top deps win
  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.2.0"}
                          'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos} nil))

  ;; deps replacement
  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos}
      {:deps {'org.clojure/tools.gitlibs {:mvn/version "0.2.64"}}}))

  ;; deps addition
  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos}
      {:extra-deps {'org.clojure/tools.gitlibs {:mvn/version "0.2.64"}}}))

  ;; override-deps
  (make-classpath-map
    {:paths ["src"]}
    (resolve-deps
      {:deps {'org.clojure/core.memoize {:mvn/version "0.5.8"}}
       :mvn/repos mvn/standard-repos}
      {:override-deps {'org.clojure/clojure {:mvn/version "1.3.0"}}})
    nil)

  (make-classpath-map
    {:paths ["src"]}
    (resolve-deps
      {:deps {'org.clojure/tools.deps.alpha {:mvn/version "0.1.40"}
              'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}}
       :mvn/repos mvn/standard-repos} nil) nil)

  ;; extra paths
  (make-classpath-map
    {:paths ["src"]}
    (resolve-deps
      {:deps {'org.clojure/clojure {:mvn/version "1.10.0"}}
       :mvn/repos mvn/standard-repos} nil)
    {:extra-paths ["extra"]})

  ;; classpath overrides
  (make-classpath-map
    {}
    (resolve-deps
      {:deps {'org.clojure/tools.deps.alpha {:mvn/version "0.1.40"}
              'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}}
       :mvn/repos mvn/standard-repos} nil)
    '{:classpath-overrides {org.clojure/clojure "foo"}})

  (resolve-deps
    {:deps '{org.clojure/clojure {:mvn/version "1.9.0"}
             org.clojure/clojurescript {:mvn/version "1.9.946"}
             reagent {:mvn/version "0.6.0"}}
     :mvn/repos mvn/standard-repos}
    nil)

  ;; err case
  (resolve-deps {:deps {'bogus {:mvn/version "1.2.3"}}
                 :mvn/repos mvn/standard-repos} nil)

  (resolve-deps {:deps {'bogus "1.2.3"}
                 :mvn/repos mvn/standard-repos} nil)

  (require
    '[clojure.tools.deps.alpha.extensions.git]
    '[clojure.tools.deps.alpha.extensions.deps])

  (resolve-deps
    {:deps {'foo {:git/url "https://github.com/clojure/core.async.git"
                  :sha "ecea2539a724a415b15e50f12815b4ab115cfd35"}}}
    nil)

  (require '[clojure.tools.deps.alpha.util.session :as session])
  (time
    (do
      (session/with-session
        (resolve-deps
          {:deps {'com.google.cloud/google-cloud-monitoring {:mvn/version "1.78.0"}}
           :mvn/repos (merge mvn/standard-repos
                        {"datomic-cloud" {:url "s3://datomic-releases-1fc2183a.s3-us-east-1.amazonaws.com/maven/releases"}})}
          nil))
      nil))

  )