(ns matchbox.auth
   (:require
    [matchbox.core :as m]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [matchbox.utils :as utils :refer [hydrate-keys ignite-keys]]
    [matchbox.registry :refer [register-listener register-auth-listener disable-auth-listener!]]
    [matchbox.serialization.keyword :as keyword]
    [matchbox.serialization.serializer :as serializer :refer [data-config]]
    cljsjs.firebase
    [goog.object :as gobj]
    [clojure.spec.alpha :as s]))

(def nil-or-val (s/or :nil nil? :value string?))

(s/def ::uid string?)
(s/def ::providerId string?)

(s/def ::email nil-or-val)
(s/def ::displayName nil-or-val)
(s/def ::phoneNumber nil-or-val)
(s/def ::photoUrl nil-or-val)

(s/def ::emailVerified boolean?)
(s/def ::isAnonymous boolean?)

(def userMetadata (s/keys :opt-un [::creationTime ::lastSignInTime]))

(s/def ::metadata userMetadata)

(s/def ::signInMethod string?)

(s/def ::refreshToken string?)

(def userInfo (s/keys
                :req-un [::uid ::providerId]
                :opt-un [::email ::displayName ::phoneNumber ::photoUrl]))

(s/def ::providerData (s/* userInfo))

(def userRecord (s/keys
                  :req-un [::uid ::providerId]
                  :opt-un [::displayName ::email ::emailVerified ::isAnonymous ::providerData
                           ::phoneNumber ::photoUrl ::metadata ::refreshToken]))


(def authCredential (s/keys :req-un [::providerId ::signInMethod]))

(s/def ::isNewUser boolean?)
(s/def ::username string?)


(s/def ::user userRecord)
(s/def ::credential authCredential)
(s/def ::operationType string?)
(s/def ::additionalUserInfo (s/keys :opt-un [::providerId ::profile ::username ::isNewUser]))

(def userCredential (s/keys
                      :req-un [::user ::credential ::operationType ::additionalUserInfo]))


(defn obj->clj
  [obj]
  (-> (fn [result key]
        (let [v (aget obj key)]
          (if (= "function" (goog/typeOf v))
            result
            (assoc result key v))))
    (reduce {} (.getKeys goog/object obj))))

(defn hydrate-obj [obj keys]
  (let [rdc-fn (fn [result k]
                 (let [k-str (name k)
                       v (gobj/get obj k-str)]
                   (assoc result k v)))]
    (reduce rdc-fn {} keys)))

(defn keywordize-keys
  [map]
  (into {} (for [[k v] map]
             [(keyword k) v])))

(defn hydrate-user [data]
  (debug "hydrating user" )
  (let [keys   [:uid :displayName :email :emailVerified :isAnonymous :metadata :phoneNumber :photoURL :providerData :providerId :refreshToken]
        user   (-> (hydrate-obj data keys)
                 (update :metadata hydrate-obj [:creationTime :lastSignInTime])
                 (update :providerData
                   (fn [ps]
                     (->> ps seq
                       (map #(hydrate-obj % [:uid :providerId :email :displayName :phoneNumber :photoUrl]) )))))
        ]
    (debug "hydrated User:" user)
    user))

(defn hydrate-user-credential [data]
  (debug "hydrating UserCredential" (.keys js/Object data))
  (let [user-cred (-> data
                    (hydrate-obj [:user :credential :operationType :additionalUserInfo])
                    (update :user hydrate-user)
                    (update :credential hydrate-obj [:providerId :signInMethod])
                    (update :additionalUserInfo hydrate-obj [:providerId :profile :username :isNewUser])
                    (update-in [:additionalUserInfo :profile] #(-> % js->clj keywordize-keys)))]
    (debug "hydrated UserCredential:" user-cred)
    (debug "IsValid?" (s/valid? userCredential user-cred))
    user-cred))



(defn auth-facebook [cb]

  ;(m/auth-with-oauth-popup
  ;  (:fire @ext-state)
  ;  "facebook"
  ;  (fn [err result] (cb err result))
  ;  {:scope "email,user_friends"})

  (debug "NEW FACEBOOK AUTH")

  (let [provider (doto (js/firebase.auth.FacebookAuthProvider.)
                   (.addScope "email"))]
    (try
      (.then (.signInWithPopup (js/firebase.auth) provider)
        (fn [result] (cb nil (:user (hydrate-user-credential result))))
        #(cb % nil))
      (catch js/Object ex
        (severe "AUTH ERROR" ex)))
    )
  )

(defn auth-password [credentials cb]
  (try
    ;(m/auth
    ; (:fire @ext-state)
    ; (:email credentials)
    ; (:password credentials)
    ; ;(fn [err result] (cb err (m/hydrate result)))
    ; cb)
    (.then (.signInWithEmailAndPassword (js/firebase.auth) (:email credentials) (:password credentials))
      (fn [result]
        (let [result (hydrate-user result)]
          (debug "AUTH-PASSWORD RESULT" result)
          (cb nil result))
        )
      #(cb % nil))
    (catch js/Object ex
      (severe "AUTH ERROR" ex))))


(defn auth-anon [cb]
  (debug "Firebase Auth Anon")
  (try
    (..
      (js/firebase.auth)
      (signInAnonymously)
      (then
        #(cb nil %))
      (catch
        #(cb % nil)))

    ;(.. app
    ;  auth
    ;  (signInAnonymously)
    ;  (then (wrap-auth-cb cb)))

    (catch js/Object ex (severe "ANON AUTH ERROR" ex))))


(defn setup-firebase! [reconciler]
  (debug "SETTING UP FIREBASE")
  (let [target-mode (:target-mode @reconciler)
        apikey      (if (= :prod target-mode)
                      "prod-key"
                      "dev-key")
        app-domain  (if (= :prod target-mode)
                      "prod-domain"
                      "dev-domain")
        bucket      (if (= :prod target-mode)
                      "prod-bucket"
                      "dev-bucket")
        opts        (m/init-web-options apikey app-domain bucket)
        app         (m/init-app opts)
        ref         (.ref (js/firebase.database))]

    ;(swap! state assoc :app app)
    ;(swap! state assoc :ref ref)

    (.onAuthStateChanged
      (.auth app)
      (fn [data]
        (debug "AUTH DATA!!!" data)

        (if data
          (let [data (hydrate-user data)]
            (debug "onAuth!!!" data)
            (debug "AUTH UID" (:uid data))
            (when providerData
              (debug "AUTH PROVIDER DATA" providerData))
            ;(om/merge! reconciler {:ui/auth {:uid (:uid data) :fb-data data}})
            ;(listen-data ref data reconciler)
            )
          (do
            (debug "deAuthed")
            ;(om/merge! reconciler {:ui/auth nil :pledges nil :activist nil})
            ))))))

(defn ^:export unauth []
  (m/unauth (:fire @ext-state)))