(ns cylon.impl.signup
  (:require
   [clojure.tools.logging :refer :all]
   [cylon.authentication :refer (InteractionStep get-location step-required?)]
   [cylon.session :refer (session respond-with-new-session! assoc-session-data!)]
   [cylon.token-store :refer (create-token! get-token-by-id)]
   [com.stuartsierra.component :as component]
   [modular.bidi :refer (WebService path-for)]
   [hiccup.core :refer (html)]
   [ring.middleware.params :refer (wrap-params)]
   [ring.middleware.cookies :refer (cookies-response wrap-cookies)]
   [cylon.user :refer (add-user! user-email-verified!)]
   [cylon.totp :as totp]
   [cylon.totp :refer (OneTimePasswordStore set-totp-secret)]
   [cylon.totp :refer (OneTimePasswordStore get-totp-secret totp-token)]
   [schema.core :as s ]))

(defprotocol SignupFormRenderer
  (render-signup-form [_ req model]))

(defprotocol WelcomeRenderer
  (render-welcome [_ req model]))

(defprotocol EmailVerifiedRenderer
  (render-email-verified [_ req model]))

(defprotocol ResetPasswordRenderer
  (render-reset-password [_ req model]))


;; One simple component that does signup, reset password, login form. etc.
;; Mostly you want something simple that works which you can tweak later - you can provide your own implementation based on the reference implementation

(defprotocol Emailer
  (send-email [_ email body]))

(defn make-verification-link [req code email]
  (let [values  ((juxt (comp name :scheme) :server-name :server-port) req)
        verify-user-email-path (path-for req ::verify-user-email)]
    (apply format "%s://%s:%d%s?code=%s&email=%s" (conj values verify-user-email-path code email))))

(defrecord SignupWithTotp [appname renderer session-store fields user-domain verification-code-store emailer fields-reset]
  WebService
  (request-handlers [this]
    {::GET-signup-form
     (fn [req]
       (let [response {:status 200
                       :body (render-signup-form
                              renderer req
                              {:form {:method :post
                                      :action (path-for req ::POST-signup-form)
                                      :fields fields}})}]
         (if
             ;; In the absence of a session...
             (not (session session-store req))
           ;; We create an empty one. This is because the POST handler
           ;; requires that a session exists within which it can store the
           ;; identity on a successful login
           (respond-with-new-session! session-store req {} response)
           response)
         ))

     ::POST-signup-form
     (->
      (fn [req]
        (debugf "Processing signup")
        (let [identity (get (:form-params req) "user")
              password (get (:form-params req) "password")
              email (get (:form-params req) "email")
              name (get (:form-params req) "name")
              totp-secret (when (satisfies? OneTimePasswordStore user-domain)
                            (totp/secret-key))

              ;; TODO Possibly we should encrypt and decrypt the verification-code (symmetric)
              verification-code (str (java.util.UUID/randomUUID))
              verification-store (create-token! verification-code-store verification-code {:email email :name name})]

          ;; Add the user
          (add-user! user-domain identity password
                     {:name name
                      :email email})

          ;; Add on the totp-secret
          (when (satisfies? OneTimePasswordStore user-domain)
            (set-totp-secret user-domain identity totp-secret))

          ;; TODO: Send the email to the user now!
          (when emailer
            (send-email emailer email
                        (format "Thanks for signing up with %s. Please click on this link to verify your account: %s"
                                appname (make-verification-link req verification-code email))))

          ;; Create a session that contains the secret-key



          (do
            (assoc-session-data! session-store req {:cylon/identity identity :name name ; duplicate code!
                                                    :totp-secret totp-secret} )
            {:status 200
             :body "Thank you! - you gave the correct information!"})))
      wrap-params wrap-cookies)


     ::verify-user-email
     (-> (fn [req]
           (let [body (if-let [[email code] [ (get (:params req) "email") (get (:params req) "code")]]
                        (if-let [store (get-token-by-id (:verification-code-store this) code)]
                          (if (= email (:email store))
                            (do (user-email-verified! (:user-domain this) (:name store))
                                (format "Thanks, Your email '%s'  has been verified correctly " email ))
                            (format "Sorry but your session associated with this email '%s' seems to not be logic" email))
                          (format "Sorry but your session associated with this email '%s' seems to not be valid" email))

                        (format "Sorry but there were problems trying to retrieve your data related with your mail '%s' " (get (:params req) "email"))
                        )]
             {:status 200
              :body (render-email-verified
                     renderer req
                     {:message body})}))
         wrap-params)

     ::reset-password-form
     (fn [req]
       {:status 200
        :body (render-reset-password
               renderer req
               {:form {:method :post
                       :action (path-for req ::process-reset-password)
                       :fields fields-reset}})})

     ::process-reset-password
     (-> (fn [req] {:status 200 :body (format "Thanks for reseting pw. Old pw: %s. New pw: %s"
                                              (get (:form-params req) "old_pw")
                                              (get (:form-params req) "new_pw"))})
         wrap-params)
     })

  (routes [this]
    ["/" {"signup" {:get ::GET-signup-form
                    :post ::POST-signup-form}
          "verify-email" {:get ::verify-user-email}
          "reset-password" {:get ::reset-password-form
                            :post ::process-reset-password}
          }])

  (uri-context [this] "/basic")



  InteractionStep
  (get-location [this req]
    (path-for req ::GET-signup-form))
  (step-required? [this req] true))

(defn new-signup-with-totp [& {:as opts}]
  (component/using
   (->> opts
        (merge {:appname "cylon"
                :fields [{:name "user" :label "User" :placeholder "userid"}
                         {:name "password" :label "Password" :password? true :placeholder "password"}
                         {:name "name" :label "Name" :placeholder "name"}
                         {:name "email" :label "Email" :placeholder "email"}]
                :fields-reset [
                               {:name "old_pw" :label "Old Password" :password? true :placeholder "old password"}
                               {:name "new_pw" :label "New Password" :password? true :placeholder "new password"}
                               {:name "new_pw_bis" :label "Repeat New Password" :password? true :placeholder "repeat new password"}]
                })
        (s/validate {:appname s/Str
                     :fields [{:name s/Str
                               :label s/Str
                               (s/optional-key :placeholder) s/Str
                               (s/optional-key :password?) s/Bool}]
                     :fields-reset [{:name s/Str
                                     :label s/Str
                                     (s/optional-key :placeholder) s/Str
                                     (s/optional-key :password?) s/Bool}]
                     (s/optional-key :emailer) (s/protocol Emailer)})
        map->SignupWithTotp)
   [:user-domain :session-store :renderer :verification-code-store]))


(defrecord WelcomeNewUser [renderer user-domain appname session-store]
  WebService
  (request-handlers [this]
    {::GET-welcome-new-user
     (fn [req]
       ;; TODO remember our (optional) email validation step
       (println "GET-welcome-new-user")
       (let [session (session session-store req)]
         {:status 200
          :body
          (render-welcome renderer req
                          {:message [:div
                                     [:p (format "Thank you for signing up %s!"  (:name session))]
                                     (when (satisfies? OneTimePasswordStore user-domain)
                                       (let [totp-secret (:totp-secret session)]
                                         [:div
                                          [:p "Please scan this image into your 2-factor authentication application"]
                                          [:img {:src (totp/qr-code (format "%s@%s" identity appname) totp-secret) }]
                                          [:p "Alternatively, type in this secret into your authenticator application: " [:code totp-secret]]
                                          ]))
                                     [:p "Please check your email and click on the verification link"]

                                     ;; We can keep this person 'logged in' now, as soon as their
                                     ;; email is verified, we can request an access code for
                                     ;; them. A user can be already authenticated with the
                                     ;; authorization service when the client application
                                     ;; requests an access code to use on that user's behalf.

                                     ;; One of the conditions for granting scopes to a client app
                                     ;; could be that the user's email has been verified. If not,
                                     ;; the user can continue, just can't do certain things such
                                     ;; as create topics (or anything we might need to know the
                                     ;; user's email address for).

                                     ;; I think the TOTP functionality could be made optional,
                                     ;; but yes, we probably could do a similar component without
                                     ;; it. Strike the balance between unreasonable conditional logic and
                                     ;; code duplication.
                                     ]
                           :form {:method :post
                                  :action (path-for req ::POST-welcome-new-user)}
                           }
                          )}))
     ::POST-welcome-new-user
     (fn [req]
       (debugf "Processing signup bis")
       {:status 200
        :body "Thank you! - you gave the correct information!"}
       )})

  (routes [this]
    ["/" {"welcome_user" {:get ::GET-welcome-new-user
                          :post ::POST-welcome-new-user}
          }])

  (uri-context [this] "/basic")


  InteractionStep
  (get-location [this req]
    (path-for req ::GET-welcome-new-user))
  (step-required? [this req] true))

(defn new-welcome-new-user [& {:as opts}]
  (component/using
   (->> opts
        (merge {:appname "cylon"
                })
        (s/validate {:appname s/Str})
        map->WelcomeNewUser)
   [:session-store :renderer :user-domain]))
