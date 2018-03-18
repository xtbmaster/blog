(ns blog.auth
  (:require
    [rum.core :as rum]
    [clojure.set :as set]
    [blog.core :as blog]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [clojure.java.shell :as shell]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as session.cookie])
  (:import
    [java.security SecureRandom]))

(defonce *tokens (atom {}))
(def session-ttl (* 1000 86400 14)) ;; 14 days
(def token-ttl-ms (* 1000 60 15)) ;; token life time 15 min


(defn random-bytes
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (java.security.SecureRandom.) seed)
    seed))


(defn save-bytes! [file ^bytes bytes]
  (with-open [os (io/output-stream (io/file file))]
    (.write os bytes)))


(defn read-bytes [file len]
  (with-open [is (io/input-stream (io/file file))]
    (let [res (make-array Byte/TYPE len)]
      (.read is res 0 len)
      res)))


(when-not (.exists (io/file "blog_data/COOKIE_SECRET"))
  (save-bytes! "blog_data/COOKIE_SECRET" (random-bytes 16)))

(def cookie-secret (read-bytes "blog_data/COOKIE_SECRET" 16))



(defn send-email! [{:keys [to subject body]}]
  (println "[ Email sent ]\nTo:" to "\nSubject:" subject "\nBody:" body))
;; (shell/sh
;;   "mail"
;;   "-s"
;;   subject
;;   to
;;   "-a" "Content-Type: text/html"
;;   "-a" "From: Grumpy Admin <admin@grumpy.website>"
;;   :in body)

(defn gen-token []
  (str
    (blog/encode (rand-int Integer/MAX_VALUE) 5)
    (blog/encode (rand-int Integer/MAX_VALUE) 5)))


(defn get-token [email]
  (when-some [token (get @*tokens email)]
    (let [ created (:created token)]
      (when (<= (blog/age created) token-ttl-ms)
        (:value token)))))


(defn- expire-session [handler]
  (fn [req]
    (let [created (:created (:session req))]
      (if (and (some? created)
            (> (blog/age created) session-ttl))
        (handler (dissoc req :session))
        (handler req)))))


(defn wrap-session [handler]
  (-> handler
    (expire-session)
    (session/wrap-session
      { :store (session.cookie/cookie-store { :key cookie-secret})
        :cookie-name "blog_session"
        :cookie-attrs { :http-only true
                        :secure false}})))

(defn user [req]
  (or blog/forced-user
    (get-in req [:session :user])))

(defn check-session [req]
  (when (nil? (user req))
    (blog/redirect "forbidden" { :redirect-url (:uri req)})))


(rum/defc email-sent-page [message]
  (blog/page { :title "...."
               :styles ["authors.css"]}
    [:.email-sent
      [:.email_sent_message message]]))


(rum/defc forbidden-page [redirect-url email]
  (blog/page { :title "Entrance"
               :styles ["authors.css"]}
    [:form.forbidden
      { :action "/send-email" :method "post"}
      [:.form_row
        [:input { :type "text"
                  :name "email"
                  :placeholder "E-mail"
                  :autofocus true}]
        [:input { :type "hidden"
                  :name "redirect-url"
                  :value redirect-url}]]
      [:.form_row
        [:button "Send a letter"]]]))


(compojure/defroutes routes

  (compojure/GET "/forbidden" [:as req]
    (let [ redirect-url (get (:params req) "redirect-url")
           user (get-in (:cookies req) ["blog_user" :value])
           email (:email (blog/author-by :user user))]
      (blog/html-response (forbidden-page redirect-url email))))

  (compojure/GET "/authenticate" [:as req] ;; ?email=...&token=...&redirect-url=...
    (let [ email        (get (:params req) "email")
           user         (:user (blog/author-by :email email))
           token        (get (:params req) "token")
           redirect-url (get (:params req) "redirect-url")]
      (if (= token (get-token email))
        (do
          (swap! *tokens dissoc email)
          (assoc
            (blog/redirect redirect-url)
            :cookies { "blog_user" { :value user}}
            :session { :user    user
                       :created (blog/now)}))
        { :status 403
          :body   "403 Bad token"})))

  (compojure/GET "/logout" [:as req]
    (assoc
      (blog/redirect "/")
      :session nil))


  (compojure/POST "/send-email" [:as req]
    (let [ params (:params req)
           email (get params "email")
           user (:user (blog/author-by :email email))]
      (cond
        (nil? (blog/author-by :email email))
        (blog/redirect "/email-sent" { :message (str "You are not the author, " email)})
        (some? (get-token email))
        (blog/redirect "/email-sent" { :message "Token is still alive, check your email"})
        :else
        (let [ token (gen-token)
               redirect-url (get params "redirect-url")
               link (blog/url (str blog/hostname "/authenticate")
                      { :email email
                        :token token
                        :redirect-url redirect-url})]
          (swap! *tokens assoc email { :value token :created (blog/now)})
          (send-email!
            { :to      email
              :subject (str "Enter to Blog " (blog/format-date (blog/now)))
              :body    (str "<html><div style='text-align: center;'><a href='" link "' style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Enter to site!</a></div></html>")})
          (blog/redirect "/email-sent" { :message (str "Check your mail, " email)})))))

  (compojure/GET "/email-sent" [:as req]
    (blog/html-response (email-sent-page (get-in req [:params "message"])))))
