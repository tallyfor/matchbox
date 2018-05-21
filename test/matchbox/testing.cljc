(ns matchbox.testing
  #?(:cljs
     (:require-macros
       [cljs.core.async.macros :refer [go]]))
  (:require
    [matchbox.core :as m]
    [matchbox.async :as ma]
    [#?(:clj clojure.core.async
        :cljs cljs.core.async) :refer [<! #?@(:clj [go <!! chan])]])
  #?(:clj
     (:import
       (com.google.firebase
         FirebaseApp)

       ;(com.google.firebase.auth
       ;  FirebaseAuth
       ;  FirebaseToken
       ;  FirebaseAuthException
       ;  ExportedUserRecord
       ;  UserRecord
       ;  UserInfo
       ;  )

       (com.google.firebase.database
         ;ChildEventListener
         ;DatabaseReference$CompletionListener
         ;ValueEventListener
         ;DatabaseError
         ;DatabaseException
         ;DatabaseReference
         ;DataSnapshot
         FirebaseDatabase)

       ;(java.io FileInputStream)

       ;(com.google.auth.oauth2 GoogleCredentials)
       ;[com.google.api.core ApiFuture]


       (java.util HashMap ArrayList))))

(def pending (atom {}))
(def errors (atom {}))

(defn setup-firebase! []
  (let [opts #?(:clj (m/init-server-options "matchbox-test" "test/matchbox/matchbox-tester-credentials.json")
                :cljs (m/init-web-options "test-key" "matchbox-test"))
        app          (m/init opts)]
    app))

(defn get-firebase []
  (or (first (FirebaseApp/getApps)) (setup-firebase!)))

(defn root-ref
  ([]
   (let [app (get-firebase)
         ref (.getReference (FirebaseDatabase/getInstance app))]
     #?(:cljs (-> ref .onDisconnect .remove))
     ref))
  ([korks]
    (m/get-in (root-ref) korks)))

(defn random-ref []
  (let [ref (root-ref)
        ref (m/get-in ref (str (rand-int 100000)))]
    ;; clear data once connection closed, having trouble on JVM with reflection
    #?(:cljs (-> ref .onDisconnect .remove))
    ref))

#?(:clj
   (defn cljs-env?
     "Take the &env from a macro, and tell whether we are expanding into cljs."
     [env]
     (boolean (:ns env))))

#?(:clj
   (defmacro if-cljs
     "Return then if we are generating cljs code and else for Clojure code.
      https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
     [then else]
     (if (cljs-env? &env) then else)))

(defmacro block-test
  "Ensuring blocking or continuation, run forms within a go block"
  [& body]
  `(let [complete# (~'chan)]
     (~'go (let [res# (or (try ~@body
                               (if-cljs
                                 '(catch js/Object e# e#)
                                 '(catch Exception e# e#)))
                        true)]
             (~'>! complete# res#)))
     (if-cljs
       '(async done (go (<! complete#) (done)))
       '(<!! complete#))))

(defmacro is=
  "Test next value delivered from channel matches expectation"
  [expect expr]
  `(block-test
     (~'is (= ~expect (~'<! ~expr)))))

(defmacro with<
  "Test next value delivered from channel matches expectation"
  [ref bind & body]
  `(block-test
     (let [~bind (~'<! (ma/deref< ~ref))]
       ~@body)))

(defmacro round-trip= [expectation data]
  `(block-test
     (let [ref# (random-ref)]
       (m/reset! ref# ~data)
       (let [result# (~'<! (ma/deref< ref#))]
         (~'is (= ~expectation result#))))))

(defmacro round-trip< [data bind & body]
  `(let [ref# (random-ref)]
     (m/reset! ref# ~data)
     (with< ref# ~bind ~@body)))

