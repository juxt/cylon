;; Copyright © 2014, JUXT LTD. All Rights Reserved.

(ns cylon.session
  (:require
   [com.stuartsierra.component :as component]
   [modular.ring :refer (ring-handler RingHandler)]
   #_[cylon.request :refer (HttpRequestAuthenticator authenticate-request failed-authentication)]
   [ring.middleware.cookies :refer (cookies-request)]
   [schema.core :as s]))

(defprotocol SessionStore
  (start-session! [_ username]) ; return cookie map compatible with wrap-cookies
  (get-session [_ request])
  (end-session! [_ value]))

#_(defrecord SessionBasedRequestAuthenticator [http-session-store user-roles]
  HttpRequestAuthenticator
  (authenticate-request [_ request]
    (when-let [session (get-session http-session-store
                                    (-> request cookies-request :cookies (get "session") :value))]
      {:session session ; retain compatibility with Ring's wrap-session
       ::session session
       ::username (:username session)})))

#_(defn new-session-based-request-authenticator [& {:as opts}]
  (->> opts
       (s/validate {:session-store (s/protocol SessionStore)})
       map->SessionBasedRequestAuthenticator))

#_(defn wrap-authentication
  "Ring middleware to pre-authenticate a request through an authenticator. If
given, the failure-handler is given the request to handle in the event
that authentication fails."
  ([h authenticator failure-handler]
     (fn [req]
       (let [auth (authenticate-request authenticator req)]
         (cond auth (h (merge req auth))
               failure-handler (failed-authentication failure-handler req)
               ;; Continue without merging auth
               :otherwise (h req)))))
  ([h authenticator]
     (wrap-authentication h authenticator nil)))

;; This record wraps an existing RingHandler and sets
;; authentication entries in the incoming request, according to its
;; protection system dependency.
#_(defrecord AuthenticationInterceptor []
  RingHandler
  (ring-handler [this]
    (-> (:ring-handler this)
        ring-handler
        (wrap-authentication
         (new-session-based-request-authenticator
          :session-store (:session-store this))))))

#_(defn new-authentication-interceptor
  ""
  []
  (component/using (->AuthenticationInterceptor) [:session-store :ring-handler]))
