(ns clojure.core.async.impl.ioc-alt
  (:require [clojure.core.async.impl.ioc-macros :refer :all :as m]
            [clojure.core.async.impl.dispatch :as dispatch]  
            [clojure.core.async.impl.protocols :as impl]))

(defrecord Park [ids cont-block]
  IInstruction
  (reads-from [this] ids)
  (writes-to [this] [])
  (block-references [this] [])
  (emit-instruction [this state-sym]
    (let [[ports opts] ids]
      `(when-let [cb# (clojure.core.async/do-alts
                       (fn [val#]
                         (m/async-chan-wrapper
                          (aset-all! ~state-sym ~VALUE-IDX val# ~STATE-IDX ~cont-block)))
                           ~ports
                           ~opts)]
         (aset-all! ~state-sym ~VALUE-IDX @cb# ~STATE-IDX ~cont-block ~ACTION-IDX ::m/recur)))))


(defmethod sexpr-to-ssa 'clojure.core.async.impl.ioc-alt/alts!
  [[_ ports & {:as args}]]
  (gen-plan
   [ids (all (map item-to-ssa [ports args]))
    cont-block (add-block)
    park-id (add-instruction (->Park ids cont-block))
    _ (set-block cont-block)
    ret-id (add-instruction (->Const ::m/value))]
   ret-id))

