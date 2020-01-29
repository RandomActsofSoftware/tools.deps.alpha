;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha.util.concurrent
  (:import
    [java.util.concurrent Callable Future ThreadFactory ExecutorService Executors TimeUnit]))

(set! *warn-on-reflection* true)

(defonce thread-factory
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. r)
        (.setName "tools.deps worker")
        (.setDaemon true)))))

(defn new-executor
  ^ExecutorService [^long n]
  (Executors/newFixedThreadPool n ^ThreadFactory thread-factory))

(def processors (.availableProcessors (Runtime/getRuntime)))

(defn submit-task
  ^Future [^ExecutorService executor f]
  (let [frame (clojure.lang.Var/cloneThreadBindingFrame)
        task #(do
                (clojure.lang.Var/resetThreadBindingFrame frame)
                (f))]
    (.submit executor ^Callable task)))

(defn shutdown-on-error
  [^ExecutorService executor]
  (.shutdownNow executor)
  (.awaitTermination executor 1 TimeUnit/SECONDS))

