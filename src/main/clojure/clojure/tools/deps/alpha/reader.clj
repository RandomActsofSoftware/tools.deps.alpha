;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha.reader
  (:require [clojure.java.io :as jio]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.deps.alpha.util.dir :as dir]
            [clojure.tools.deps.alpha.util.io :as io])
  (:import [java.io File InputStreamReader BufferedReader]
           [clojure.lang EdnReader$ReaderException]))

(set! *warn-on-reflection* true)

(defn- scrape-clojure-env
  []
  (io/printerrln "WARNING: clojure-env has been deprecated and will be removed.")
  (let [{:keys [out exit] :as result} (sh/sh "clojure" "-Sdescribe")]
    (if (zero? exit)
      (read-string out)
      (throw (ex-info "Unable to locate Clojure's edn files" result)))))

(def ^{:deprecated "0.7.516"} clojure-env
  "Returns a map describing the environment known to clj/clojure:
  {:config-files [ ... ]}.

  DEPRECATED - use (reader/default-deps) instead!"
  (memoize scrape-clojure-env))

(defn- io-err
  ^Throwable [fmt ^File f]
  (let [path (.getAbsolutePath f)]
    (ex-info (format fmt path) {:path path})))

(defn- slurp-edn-map
  "Read the file specified by the path-segments, slurp it, and read it as edn."
  [^File f]
  (let [val (try (io/slurp-edn f)
              (catch EdnReader$ReaderException e (throw (io-err (str (.getMessage e) " (%s)") f)))
              (catch RuntimeException t
                (if (str/starts-with? (.getMessage t) "EOF while reading")
                  (throw (io-err "Error reading edn, delimiter unmatched (%s)" f))
                  (throw (io-err (str "Error reading edn. " (.getMessage t) " (%s)") f)))))]
    (if (map? val)
      val
      (throw (io-err "Expected edn map in: %s" f)))))

(defn- canonicalize-sym
  ([s]
    (canonicalize-sym s nil))
  ([s file-name]
   (if (simple-symbol? s)
     (let [cs (as-> (name s) n (symbol n n))]
       (io/printerrln "DEPRECATED: Libs must be qualified, change" s "=>" cs
         (if file-name (str "(" file-name ")") ""))
       cs)
     s)))

(defn- canonicalize-exclusions
  [{:keys [exclusions] :as coord} file-name]
  (if (seq (filter simple-symbol? exclusions))
    (assoc coord :exclusions (mapv #(canonicalize-sym % file-name) exclusions))
    coord))

(defn- canonicalize-dep-map
  [deps-map file-name]
  (when deps-map
    (reduce-kv (fn [acc lib coord]
                 (let [new-lib (if (simple-symbol? lib) (canonicalize-sym lib file-name) lib)
                       new-coord (canonicalize-exclusions coord file-name)]
                   (assoc acc new-lib new-coord)))
      {} deps-map)))

(defn- canonicalize-all-syms
  ([deps-edn]
    (canonicalize-all-syms deps-edn nil))
  ([deps-edn file-name]
   (walk/postwalk
     (fn [x]
       (if (map? x)
         (reduce (fn [xr k]
                   (if-let [xm (get xr k)]
                     (assoc xr k (canonicalize-dep-map xm file-name))
                     xr))
           x #{:deps :default-deps :override-deps :extra-deps :classpath-overrides})
         x))
     deps-edn)))

(defn slurp-deps
  "Read a single deps.edn file from disk and canonicalize symbols,
  return a deps map."
  [^File dep-file]
  (-> dep-file slurp-edn-map (canonicalize-all-syms (.getPath dep-file))))

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
      nil vals)))

(defn merge-deps
  "Merge multiple deps maps from left to right into a single deps map.

  DEPRECATED - use clojure.tools.deps.alpha/merge-edns instead"
  {:deprecated "Use clojure.tools.deps.alpha/merge-edns instead"}
  [deps-maps]
  (apply merge-with merge-or-replace deps-maps))

(def ^:const install-deps-path
  "Resource path to the install deps.edn resource"
  "clojure/tools/deps/deps.edn")

(defn install-deps
  "Read the install deps.edn resource from the classpath"
  []
  (let [url (jio/resource install-deps-path)]
    (io/read-edn (BufferedReader. (InputStreamReader. (.openStream url))))))

(defn user-deps-location
  "Use the same logic as clj to return the expected location of the user
  config path. Note that it's possible no file may exist at this location."
  []
  (let [config-env (System/getenv "CLJ_CONFIG")
        xdg-env (System/getenv "XDG_CONFIG_HOME")
        home (System/getProperty "user.home")
        config-dir (cond config-env config-env
                         xdg-env (str xdg-env File/separator "clojure")
                         :else (str home File/separator ".clojure"))]
    (str config-dir File/separator "deps.edn")))

(defn read-deps
  "Read the built-in clojure/tools/deps/deps.edn resource, and a set of deps-files,
  and merge them left to right into a single deps map."
  [deps-files]
  (let [built-in (install-deps)
        dep-maps (map slurp-deps deps-files)]
    (merge-deps (into [built-in] dep-maps))))

(defn default-deps
  "Use the same logic as clj to build the set of deps.edn files to load.
  These can be passed to read-deps to replicate what clj does."
  []
  (filterv #(-> % jio/file .exists)
    [(user-deps-location) (str dir/*the-dir* File/separator "deps.edn")]))

(defn find-edn-maps
  "Finds and returns standard deps edn maps in a map with keys
    :install-edn, :user-edn, :project-edn
  If no project-edn is supplied, use the deps.edn in current directory"
  ([]
    (find-edn-maps nil))
  ([project-edn-file]
   (let [user-loc (jio/file (user-deps-location))
         project-loc (jio/file (if project-edn-file project-edn-file (str dir/*the-dir* File/separator "deps.edn")))]
     (cond-> {:install-edn (install-deps)}
       (.exists user-loc) (assoc :user-edn (slurp-deps user-loc))
       (.exists project-loc) (assoc :project-edn (slurp-deps project-loc))))))
