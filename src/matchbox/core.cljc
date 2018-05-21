(ns matchbox.core
  (:refer-clojure
    :exclude
    [get-in set! reset! conj! swap! dissoc! deref parents key take take-last])
  #?(:clj
     (:import
       [com.google.firebase
        FirebaseApp
        FirebaseOptions
        FirebaseOptions$Builder]

       [com.google.firebase.auth
        FirebaseAuth
        FirebaseToken
        FirebaseAuthException
        ExportedUserRecord
        UserRecord
        UserInfo
        ]

       [com.google.firebase.database
        ChildEventListener
        DatabaseReference$CompletionListener
        Logger
        Logger$Level
        Transaction$Handler
        ValueEventListener
        DatabaseError
        DatabaseException
        DatabaseReference
        DataSnapshot
        FirebaseDatabase
        ;GenericTypeIndicator<T>
        MutableData
        OnDisconnect
        Query
        ServerValue
        Transaction
        Transaction$Result]

       [java.io FileInputStream]

       [com.google.auth.oauth2 GoogleCredentials]
       ;[com.google.api.core ApiFuture]


       (java.util HashMap ArrayList)))
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [matchbox.utils :as utils :refer [hydrate-keys ignite-keys]]
    [matchbox.registry :refer [register-listener #?@(:cljs [register-auth-listener disable-auth-listener!])]]
    [matchbox.serialization.keyword :as keyword]
    [matchbox.serialization.serializer :as serializer :refer [data-config]]
    #?(:cljs cljsjs.firebase)))

;; constants

;; Distinct from nil/null in CLJS, useful for opting out of callbacks
(def undefined)

(def child-events
  [:child-added
   :child-changed
   :child-moved
   :child-removed])

(def all-events
  (conj child-events :value))

#?(:clj
   (def logger-levels
     {:debug Logger$Level/DEBUG
      :info Logger$Level/INFO
      :warn Logger$Level/WARN
      :error Logger$Level/ERROR
      :none Logger$Level/NONE}))

#?(:clj
   (defn set-logger-level! [key]
     (assert (contains? logger-levels key) (format "Unknown logger level: `%s`" key))
     (.setLogLevel ^FirebaseDatabase (.getInstance FirebaseDatabase) (logger-levels key))))

(def SERVER_TIMESTAMP
  #?(:clj ServerValue/TIMESTAMP
     :cljs js/firebase.database.ServerValue.TIMESTAMP))

;; helpers

(declare wrap-snapshot)
(declare hydrate)
(declare reset!)

(defn throw-fb-error [err & [msg]]
  (throw (ex-info (or msg "DatabaseError") {:err err})))

#?(:clj
   (defn- wrap-cb [cb]
     (reify DatabaseReference$CompletionListener
       (^void onComplete [_ ^DatabaseError err ^DatabaseReference ref]
         (if err (throw-fb-error err "Cancelled") (cb ref))))))

#?(:clj
   (defn- reify-value-listener [cb & [ds-wrapper]]
     (let [ds-wrapper (or ds-wrapper wrap-snapshot)]
       (reify ValueEventListener
         (^void onDataChange [_ ^DataSnapshot ds]
           (cb (ds-wrapper ds)))
         (^void onCancelled [_ ^DatabaseError err]
           (if err (throw-fb-error err "Cancelled") (cb ref)))))))

#?(:clj
   (defn- build-tx-handler [f args cb]
     (reify Transaction$Handler
       (^Transaction$Result doTransaction [_ ^MutableData d]
         (let [current (hydrate (.getValue d))]
           (reset! d (apply f current args))
           (Transaction/success d)))
       (^void onComplete [_ ^DatabaseError error, ^boolean committed, ^DataSnapshot d]
         (if (and cb (not error) committed)
           (cb (hydrate (.getValue d))))))))

#?(:clj
   (defn reify-child-listener [{:keys [added changed moved removed]}]
     (reify ChildEventListener
       (^void onChildAdded [_ ^DataSnapshot d ^String _]
         (if added (added (wrap-snapshot d))))
       (^void onChildChanged [_ ^DataSnapshot d ^String _]
         (if changed (changed (wrap-snapshot d))))
       (^void onChildMoved [_ ^DataSnapshot d ^String _]
         (if moved (moved (wrap-snapshot d))))
       (^void onChildRemoved [_ ^DataSnapshot d]
         (if removed (removed (wrap-snapshot d))))
       (^void onCancelled [_ ^DatabaseError err]
         (throw-fb-error err "Cancelled")))))

#?(:clj
   (defn- strip-prefix [type]
     (-> type name (str/replace #"^.+\-" "") keyword)))

(defn hydrate [x] (serializer/hydrate x))
(defn serialize [x] (serializer/serialize x))


(defn key
  "Last segment in reference or snapshot path"
  [ref]
  #?(:clj (.getKey ref)
     :cljs (.-key ref)))

(defn value
  "Data stored within snapshot"
  [snapshot]
  (hydrate
    #?(:clj (.getValue ^DataSnapshot snapshot)
       :cljs (.val snapshot))))

(defn- wrap-snapshot [snapshot]
  [(key snapshot) (value snapshot)])

;; API

(defn get-in
  "Obtain child reference from base by following korks"
  [ref korks]
  (let [path (utils/korks->path korks)]
    (if-not (seq path) ref (.child ref path))))

#?(:clj
   (defn init-server-options
     [^String credential-path ^String app-domain]
     (let [serviceAccount (FileInputStream. credential-path)]
       (.build
         (doto (FirebaseOptions$Builder.)
           (.setCredentials (GoogleCredentials/fromStream serviceAccount))
           (.setDatabaseUrl (str "https://" app-domain ".firebaseio.com")))))))

#?(:cljs
   (defn init-web-options
     ([api-key domain]
      (init-web-options api-key domain domain))
     ([apikey domain bucket]
      (let [config {:apiKey apikey
                    :authDomain (str domain ".firebaseapp.com")
                    :databaseURL (str "https://" domain ".firebaseio.com")
                    :storageBucket (str bucket ".appspot.com")}]
        config))))

(defn init-app
  "Initialize a firebase app given options"
  ([opts]
    #?(:clj (FirebaseApp/initializeApp opts)
       :cljs (.. js/firebase (initializeApp (clj->js opts)))))
  ([opts aname]
    #?(:clj (FirebaseApp/initializeApp opts aname)
       :cljs (.. js/firebase (initializeApp (clj->js opts) aname)))))

(defn init!
  "Initialize a firebase app given a domain string and an api-key (cljs) or credentials path (clj)"
  ([app-domain apikey-or-credentials]
   (init! app-domain apikey-or-credentials nil))
  ([app-domain apikey-or-credentials app-name]
   (let [opts #?(:cljs (init-web-options apikey-or-credentials app-domain)
                 :clj (init-server-options app-domain apikey-or-credentials))]
     (if app-name
       (init-app opts app-name)
       (init-app opts)))))

(defn connect
  "Create a reference for an existing firebase app"
  ([url]
    #?(:clj (-> (FirebaseDatabase/getInstance)
              (.getReferenceFromUrl url))
       :cljs (.refFromURL (js/firebase.database) url)))
  ([url korks]
   (get-in (connect url) korks)))

(defn parent
  "Immediate ancestor of reference, if any"
  [ref]
  (and ref
    #?(:clj (.getParent ref)
       :cljs (.-parent ref))))

(defn parents
  "Probably don't need this. Or maybe we want more zipper nav (siblings, in-order, etc)"
  [ref]
  (take-while identity (iterate parent (parent ref))))

(defn deref
  ([ref cb]
   (deref ref cb nil))
  ([ref cb err-fn]
    #?(:clj (.addListenerForSingleValueEvent ref (reify-value-listener cb value))
       :cljs (.once ref "value" (comp cb value) err-fn))))

(defn- get-children [snapshot]
  (mapv value
    #?(:clj (.getChildren snapshot)
       ;; perhaps use js array if too much latency here for larger lists
       :cljs (let [kids (atom [])]
               ;; must return falsey else iteration ends
               (.forEach snapshot #(do (clojure.core/swap! kids conj %) undefined))
               @kids))))

(defn deref-list [ref cb]
  #?(:clj (.addListenerForSingleValueEvent ref (reify-value-listener cb get-children))
     :cljs (.once ref "value" (comp cb #(get-children %)))))

(defn reset! [ref val & [cb]]
  #?(:clj
     (if-not cb
       (.setValue ref (serialize val) (wrap-cb #()))
       (.setValue ref (serialize val) (wrap-cb cb)))
     :cljs (.set ref (serialize val) (if cb
                                       (fn [err]
                                         (if err
                                           (throw-fb-error err)
                                           (cb ref)))
                                       undefined))))

(defn reset-with-priority! [ref val priority & [cb]]
  #?(:clj (if-not cb
            (.setValue ref (serialize val) priority (wrap-cb #()))
            (.setValue ref (serialize val) priority (wrap-cb cb)))
     :cljs (.setWithPriority ref (serialize val) priority
             (if cb
               (fn [err]
                 (if err
                   (throw-fb-error err)
                   (cb ref)))
               undefined))))

(defn merge! [ref val & [cb]]
  #?(:clj
     (if-not cb
       (.updateChildren ref (serialize val) (wrap-cb #()))
       (.updateChildren ref (serialize val) (wrap-cb cb)))
     :cljs
     (.update ref (serialize val) (if cb
                                    (fn [err]
                                      (if err
                                        (throw-fb-error err)
                                        (cb ref)))
                                    undefined))))

(defn conj! [ref val & [cb]]
  #?(:clj (let [r (.push ref)]
            (reset! r val cb)
            (key r))
     :cljs (let [k (atom nil)]
             (clojure.core/reset!
               k
               (key
                 (.push ref (serialize val)
                   (if cb
                     (fn [err]
                       (if err
                         (throw-fb-error err)
                         (cb (get-in ref @k))))
                     undefined))))
             @k)))

(defn swap!
  "Update value atomically, with local optimistic writes"
  [ref f & args]
  (let [[cb args] (utils/extract-cb args)]
    #?(:clj
       (.runTransaction ref (build-tx-handler f args cb) true)
       :cljs
       (let [f' #(-> % hydrate ((fn [x] (apply f x args))) serialize)]
         (.transaction ref f' (if cb
                                (fn [err commit? ds]
                                  (if err
                                    (throw-fb-error err)
                                    (cb (value ds))))
                                undefined))))))

(defn dissoc! [ref & [cb]]
  #?(:clj (if-not cb
            (.removeValue ref (wrap-cb #()))
            (.removeValue ref (wrap-cb cb)))
     :cljs (.remove ref (or cb undefined))))

(def remove! dissoc!)

(defn set-priority! [ref priority & [cb]]
  #?(:clj
     (if-not cb
       (.setPriority ref priority (wrap-cb #()))
       (.setPriority ref priority (wrap-cb cb)))
     :cljs
     (.setPriority ref priority (or cb undefined))))

;;

(defn ref? [x] (instance? #?(:clj DatabaseReference :cljs js/firebase) x))

(defn- with-ds [ref-or-ds f & [cb]]
  (if (ref? ref-or-ds)
    (let [ref ref-or-ds]
      (assert cb "Callback required when called against reference")
      #?(:clj (.addListenerForSingleValueEvent ref (reify-value-listener cb f))
         :cljs (.once ref "value" (comp cb f))))
    (let [ds ref-or-ds
          v  (f ds)]
      (if cb (cb v) v))))

(defn- -export [ds]
  (hydrate
    #?(:clj (.getValue ds true)
       :cljs (.exportVal ds))))

(defn- -priority [ds]
  (.getPriority ds))

(defn export [ref-or-ds & [cb]]
  (with-ds ref-or-ds -export cb))

(defn priority [ref-or-ds & [cb]]
  (with-ds ref-or-ds -priority cb))

(defn export-in [ref-or-ds korks & [cb]]
  (if (ref? ref-or-ds)
    (export (get-in ref-or-ds korks) cb)
    (export ref-or-ds (comp cb #(get-in % korks)))))

(defn priority-in [ref-or-ds korks & [cb]]
  (if (ref? ref-or-ds)
    (priority (get-in ref-or-ds korks) cb)
    (priority ref-or-ds (comp cb #(get-in % korks)))))

;;

(defn order-by-priority [ref]
  (.orderByPriority ref))

(defn order-by-key [ref]
  (.orderByKey ref))

(defn order-by-value [ref]
  (.orderByValue ref))

(defn order-by-child [ref key]
  (.orderByChild ref (name key)))

;;

(defn start-at
  "Limit query to start at `value` (inclusive). By default `value` is compared against
   priorities, but reacts to the `order-by-*` scope. This also affects what types
   `value can take on.

   `key` is the child key to start at, and is supported only when ordering by priority."
  [ref value & [key]]
  (let [value (if (number? value) (double value) value)]
    (if key
      (.startAt ref value (name key))
      (.startAt ref value))))

(defn end-at
  "Limit query to end at `value` (inclusive). By default `value` is compared against
   priorities, but reacts to the `order-by-*` scope. This also affects what types
   `value can take on.

   `key` is the child key to end at, and is supported only when ordering by priority."
  [ref value & [key]]
  (let [value (if (number? value) (double value) value)]
    (if key
      (.endAt ref value (name key))
      (.endAt ref value))))

(defn equal-to
  "Limit query to `value` (inclusive). By default `value` is compared against
   priorities, but reacts to the `order-by-*` scope. This also affects what types
   `value can take on.

  `key` is the child key to compare on, and is supported only when ordering by priority."
  [ref value & [key]]
  (let [value (if (number? value) (double value) value)]
    (if key
      (.equalTo ref value (name key))
      (.equalTo ref value))))

(defn take
  "Limit scope to the first `limit` items"
  [ref limit]
  (.limitToFirst ref limit))

(defn take-last
  "Limit scope to the last `limit` items"
  [ref limit]
  (.limitToLast ref limit))

;;

(defonce connected (atom true))

(defn disconnect!
  []
  #?(:clj (.goOffline (FirebaseDatabase/getInstance))
     :cljs (.goOffline (.database js/firebase)))
  (clojure.core/reset! connected false))

(defn reconnect! []
  #?(:clj (.goOnline (FirebaseDatabase/getInstance))
     :cljs (.goOnline (.database js/firebase)))
  (clojure.core/reset! connected true))

(defn connected?
  "Returns boolean around whether client is set to synchronise with server.
   Says nothing about actual connectivity."
  []
  @connected)

;(defn on-disconnect [ref]
;  #?(:cljs (.onDisconnect ref)))
;
;(defn cancel [ref-disconnect & [cb]]
;  #?(:cljs (.cancel ref-disconnect (or cb undefined))))



;;;; FIXME for auth see matchbox.auth (work in progress)


;; --------------------
;; auth

;(defn- ensure-kw-map
;  "Coerce java.util.HashMap and friends to keywordized maps"
;  [data]
;  (walk/keywordize-keys (into {} data)))

;#?(:cljs
;   (defn- auth-data->map [auth-data]
;     (hydrate auth-data)))
;
;#?(:cljs
;   (defn wrap-auth-changed [cb]
;     (if cb
;       (fn [err info]
;         (cb err (js->clj info :keywordize-keys true)))
;       identity)))
;
;#?(:cljs
;   (defn- wrap-auth-cb [cb]
;     (if cb
;       (fn [err info]
;         (cb err (js->clj info :keywordize-keys true)))
;       identity)))
;
;#?(:cljs
;   (defn create-user
;     "create-user creates a user in the Firebase built-in authentication server"
;     [app email password & [cb]]
;     (.. app
;       auth
;       (createUserWithEmailAndPassword email password)
;       (then (wrap-auth-cb cb)))))
;
;
;#?(:cljs
;   (defn auth [app email password & [cb]]
;     (.. app
;       auth
;       (signInWithEmailAndPassword email password)
;       (then (wrap-auth-cb cb)))))
;
;#?(:cljs
;   (defn auth-anon [app & [cb]]
;     (.. app
;       auth
;       (signInAnonymously)
;       (then
;         #(cb nil %))
;       (catch
;         #(cb % nil)))))
;
;#?(:cljs
;   (defn auth-custom
;     "Authenticates a Firebase client using an authentication token or Firebase Secret."
;     ([app token]
;      (auth-custom app token nil))
;     ([app token cb]
;      (.. app
;        auth
;        (signInWithCustomToken token)
;        (then (wrap-auth-cb cb))))))
;
;#?(:cljs
;   (defn auth-with-oauth-popup
;     ([auth provider]
;      (.signInWithPopup auth provider))
;     ([auth provider cb]
;      (.then (.signInWithPopup auth provider) (wrap-auth-cb cb)))))

;#?(:cljs
;   (defn auth-with-oauth-redirect
;     ([ref type]
;      (auth-with-oauth-redirect ref type undefined))
;     ([ref type cb]
;      (.authWithOAuthRedirect ref type (wrap-auth-cb cb)))
;     ([ref type cb options]
;      (.authWithOAuthRedirect ref type (wrap-auth-cb cb) (clj->js options)))))
;
;#?(:cljs
;   (defn auth-with-oauth-token
;     ([ref type token-or-obj]
;      (auth-with-oauth-token ref type token-or-obj undefined))
;     ([ref type token-or-obj cb]
;      (.authWithOAuthToken ref type token-or-obj (wrap-auth-cb cb)))
;     ([ref type token-or-obj cb options]
;      (.authWithOAuthToken ref type token-or-obj (wrap-auth-cb cb) (clj->js options)))))

;#?(:cljs
;   (defn auth-info
;     "Returns a map of uid, provider, token, expires - or nil if there is no session"
;     [app]
;     (auth-data->map (.currentUser (.auth app)))))

;;;; onAuth and offAuth are not fully wrapped yet
;
;#?(:cljs
;   (defn onAuth [app cb]
;     (register-auth-listener app cb (wrap-auth-cb cb))))
;
;#?(:cljs
;   (defn offAuth [app cb]
;     (disable-auth-listener! app cb)))
;
;#?(:cljs
;   (defn unauth [app]
;     (.signOut (.auth app))))

;; nested variants

(defn deref-in [ref korks cb]
  (deref (get-in ref korks) cb))

(defn deref-list-in [ref korks cb]
  (deref-list (get-in ref korks) cb))

(defn reset-in! [ref korks val & [cb]]
  (reset! (get-in ref korks) val cb))

(defn reset-with-priority-in! [ref korks val priority & [cb]]
  (reset-with-priority! (get-in ref korks) val priority cb))

(defn merge-in! [ref korks val & [cb]]
  (merge! (get-in ref korks) val cb))

(defn conj-in! [ref korks val & [cb]]
  (conj! (get-in ref korks) val cb))

(defn swap-in! [ref korks f & args]
  (apply swap! (get-in ref korks) f args))

(defn dissoc-in! [ref korks & [cb]]
  (dissoc! (get-in ref korks) cb))

(def remove-in! dissoc-in!)

(defn set-priority-in! [ref korks priority & [cb]]
  (set-priority! (get-in ref korks) priority cb))

;; ------------------
;; subscriptions

(defn --listen-to [ref type cb render-fn]
  #?(:clj
     (let [listener (if-not (some #{type} child-events)
                      ;; subscribe
                      (.addValueEventListener ref (reify-value-listener cb render-fn))
                      (.addChildEventListener ref (reify-child-listener
                                                    (hash-map (strip-prefix type) cb))))]
       ;; build unsubsubscribe fn
       (fn [] (.removeEventListener ref listener)))
     :cljs
     (let [type (utils/kebab->underscore type)]
       (let [listener (comp cb render-fn)]
         ;; subscribe
         (.on ref type listener)
         ;; build unsubsubscribe fn
         (fn [] (.off ref type listener))))))

(defn- -listen-to [ref type cb & [render-fn]]
  (let [render-fn (or render-fn wrap-snapshot)]
    (assert (some #{type} all-events) (str "Unknown type: " type))
    (let [unsub! (--listen-to ref type cb render-fn)]
      (register-listener ref type unsub!)
      unsub!)))

(defn- -listen-children [ref cb]
  (let [cbs    (->> child-events
                 (map (fn [type] #(vector type %)))
                 (map #(comp cb %)))
        ;; NOTE: JVM implementation could create a single listener
        unsubs (doall (map -listen-to (repeat ref) child-events cbs))]
    (fn []
      (doseq [unsub! unsubs]
        (unsub!)))))

(defn listen-to
  "Subscribe to notifications of given type
   Callback receives [<key> <value>] as only argument
   Returns an unsubscribe function"
  ([ref type cb] (-listen-to ref type cb))
  ([ref korks type cb] (-listen-to (get-in ref korks) type cb)))

(defn listen-list
  "Subscribe to updates containing full vector or children"
  ([ref cb] (-listen-to ref :value cb get-children))
  ([ref korks cb] (listen-list (get-in ref korks) cb)))

(defn listen-children
  "Subscribe to all children notifications on a reference.
   Callback receives [:event-type [<key> <value>]] as only argument
   Returns an unsubscribe function"
  ([ref cb] (-listen-children ref cb))
  ([ref korks cb] (-listen-children (get-in ref korks) cb)))
