(ns matchbox.admin-test
  (:require [clojure.test :refer :all]
            [matchbox.core :as m]
            [matchbox.async :as ma]
            [matchbox.testing :refer [get-firebase root-ref random-ref is= block-test]]
            [clojure.core.async :refer [chan <! >! go]])
  (:import
    (com.google.firebase
      FirebaseApp)

    (com.google.firebase.auth
      FirebaseAuth
      FirebaseToken
      FirebaseAuthException
      ExportedUserRecord
      UserRecord
      UserInfo
      )

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


    (java.util HashMap ArrayList)))

(deftest list-users-test
  (testing "List users"
    (let [app   (get-firebase)
          users (.getValues (.listUsers (FirebaseAuth/getInstance app) nil))
          ;ref (random-ref)
          ;val (rand-int 1000)
          ;p   (promise)
          ]
      ;(m/deref ref #(deliver p %))
      ;(m/reset! ref val)
      ;(is (= val @p))
      )))