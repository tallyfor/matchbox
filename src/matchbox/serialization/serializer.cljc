(ns matchbox.serialization.serializer
  (:refer-clojure :exclude [prn])
  (:require [clojure.walk :refer [prewalk postwalk]]
            [matchbox.serialization.keyword :as kw]))

(defprotocol ISerializer
  (hydrate [this x])
  (serialize [this x])
  (config! [this hydrate serialize]))

(deftype Serializer
  [#?(:clj ^:volatile-mutable hydrate :cljs ^:mutable hydrate)
   #?(:clj ^:volatile-mutable serialize :cljs ^:mutable serialize)]
  ISerializer
  (hydrate [_ x] (hydrate x))
  (serialize [_ x] (serialize x))
  (config! [_ h s] (set! hydrate h) (set! serialize s)))

(def data-config (->Serializer kw/hydrate kw/serialize))

(defn set-data-config! [hydrate serialize]
  (-> ^Serializer data-config
    (config! hydrate serialize)))

(defn serialize [x]
  (.serialize data-config x))

(defn hydrate [x]
  (.hydrate data-config x))