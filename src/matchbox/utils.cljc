(ns matchbox.utils
  (:refer-clojure :exclude [prn])
  (:require [clojure.string :as str]
            [matchbox.serialization.keyword :as keyword]
            [matchbox.serialization.serializer :refer [set-data-config!]]
            [clojure.walk :refer [prewalk postwalk]]))

(defn kebab->underscore [keyword]
  (-> keyword name (str/replace "-" "_")))

(defn underscore->kebab [string]
  (-> string (str/replace "_" "-") keyword))

(defn korks->path [korks]
  (if (sequential? korks)
    (str/join "/" (map name korks))
    (when korks (name korks))))

(defn no-op ([_]) ([_ _]) ([_ _ _]) ([_ _ _ & _]))

(defn extract-cb [args]
  (if (and (>= (count args) 2)
           (= (first (take-last 2 args)) :callback))
    [(last args) (drop-last 2 args)]
    [nil args]))


;(set-data-config! keyword/hydrate keyword/serialize)

#?(:clj (def repl-out *out*))

#?(:clj
    (defn prn
      "Like clojure.core/prn, but always bound to root thread's *out*"
      [& args]
      (binding [*out* repl-out]
        (apply clojure.core/prn args))))


(defn ignite-key
  "used by ignite-keys to transform a map key from clojure to firebase"
  [[k v]]
  (if
    (keyword? k)
    [(str/replace
       (str (when (namespace k)
              (str (namespace k) "_"))
         (name k)) "." "-") v]
    [(str/replace k "." "-") v]))

(defn ignite-keys
  "Recursively transform map keys from clojure namespaced keywords to firebase-friendly underscored strings."
  [m]
  (postwalk (fn [x] (if (map? x) (into {} (map ignite-key x)) x)) m))


(defn hydrate-key
  "used by hydrate-keys to transform a map key from firebase to clojure"
  [[k v]]
  (if (keyword? k)
    (let [parsed #?(:cljs (js/parseInt (name k))
                    :clj (try (Integer/parseInt (name k)) (catch NumberFormatException _ nil)))]
      (if-not #?(:cljs (js/isNaN parsed)
                 :clj (nil? parsed))
        [parsed v]
        [(keyword (str/replace-first (str/replace (name k) "/" "-") "_" "/")) v]))
    [k v]))

(defn hydrate-keys
  "Recursively transform map keys from firebase-friendly underscored strings to clojure namespaced keywords."
  [m]
  (postwalk (fn [x] (if (map? x) (into {} (map hydrate-key x)) x)) m))