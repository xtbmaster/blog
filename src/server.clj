(ns server
  (:require
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.stacktrace]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [compojure.core :as compojure]
    [compojure.route]
    [immutant.web :as web]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as session.cookie]
    [ring.middleware.multipart-params]
    [ring.middleware.params]
    [ring.util.response]
    [rum.core :as rum])
  (:import
    [java.util UUID Date]
    [org.joda.time DateTime]
    [org.joda.time.format DateTimeFormat DateTimeFormatter]))

(def date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))

(def styles (slurp (io/resource "static/styles.css")))
(def script (slurp (io/resource "static/scripts.js")))
(def authors {"arturaliiev@gmail.com" "arthur"})
(def session-ttl (* 1000 86400 14)) ;; 14 days
(def token-ttl-ms (* 1000 60 15)) ;; token life time 15 min

(.mkdirs (io/file "blog_data"))

(defonce *tokens (atom {}))

(defn render-date [^Date inst]
  (.print ^DateTimeFormatter date-formatter (DateTime. inst)))

(defn now ^Date []
  (Date.))

(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")

(defn render-html [component]
  (str "<!DOCTYPE html>\n" (rum/render-static-markup component)))

(defn encode [num len]
  (loop [num  num
          list ()
          len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
        (let [ch (nth encode-table (bit-and num 0x3F))]
          (conj list ch))
        (dec len)))))

(defn gen-token []
  (str
    (encode (rand-int Integer/MAX_VALUE) 5)
    (encode (rand-int Integer/MAX_VALUE) 5)))

(defn encode-uri-component [s]
  (-> s
    (java.net.URLEncoder/encode "UTF-8")
    (str/replace #"\+"   "%20")
    (str/replace #"\%21" "!")
    (str/replace #"\%27" "'")
    (str/replace #"\%28" "(")
    (str/replace #"\%29" ")")
    (str/replace #"\%7E" "~")))


(defn send-email! [{:keys [to subject body]}]
  (println "[ Email sent ]\nTo:" to "\nSubject:" subject "\nBody:" body)
  (shell/sh
    "mail"
    "-s"
    subject
    to
    "-a" "Content-Type: text/html"
    "-a" "From: Grumpy Admin <admin@grumpy.website>"
    :in body))

;; Parinfer breaks correct parens location when multi-arg method is used :(
(defn redirect
  [url]
  { :status 302
    :headers { "Location" url}})

(defn redirect
  [url query]
  (let [query-str (map
                    (fn [[k v]]
                      (str (name k) "=" (encode-uri-component v)))
                    query)]
    { :status 302
      :headers { "Location" (str url "?" (str/join "&" query-str))}}))


(defn zip [coll1 coll2] ;; TODO see map-indexed
  (map vector coll1 coll2))


(defn next-post-id []
  (str (encode (quot (System/currentTimeMillis) 1000) 6)
    (encode (rand-int (* 64 64 64)) 3)))


(defn save-post! [post pictures]
  (let [ dir           (io/file (str "blog_data/posts/" (:id post)))
         picture-names (for [[picture idx] (map vector pictures (range))
                              :let [ in-name  (:filename picture)
                                     [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                         (str (:id post) "_" (inc idx) ext))]
    (.mkdir dir)
    (doseq [[picture name] (map vector pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (.delete (:tempfile picture)))
    (spit (io/file dir "post.edn")
      (pr-str (assoc post :pictures (vec picture-names))))))


(defn check-session [req]
  (when (nil? (get-in req [:session :user]))
    (redirect "forbidden" { :redirect-url (:uri req)})))


(defn print-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (.printStackTrace e)
        { :status 500
          :headers { "Content-Type" "text/html; charset=utf-8"} 
          :body (with-out-str
                  (clojure.stacktrace/print-stack-trace (clojure.stacktrace/root-cause e)))}))))


(rum/defc post [post]
  [:.post
    [:.post_side
      [:img {:src (str "/i/" (:author post) ".gif")}]]
    [:.post_body
      (for [name (:pictures post)]
        [:img.post_img { :src (str "/post/" (:id post) "/" name)}])
      (for [[p idx] (zip (str/split (:body post) #"\n+") (range))]
        [:p.post_p
          (when (== 0 idx)
            [:span.post_author (:author post) ": "])
          p])
      [:p.post_meta (render-date (:created post)) "//" [:a {:href (str "/post/" (:id post))} "Link"]]]])


(rum/defc page [opts & childern]
  (let [{ :keys [title index?] ;; getting :title and :index from opts, if not found - default
          :or { title "Blog"
                index? false}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:title title]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:style {:dangereouslySetInnerHTML {:__html styles}}]]
      [:body
        [:header
          (if index?
            [:h1.title title]
            [:h1.title [:a.title_back {:href "/"} title]])
          [:p.subtitle "link"]]
        childern
        [:footer
          [:a {:href "https://github.com/xtbmaster"} "Arthur Aliiev"]
          ". 2018. All rights aren't reserved."]
        [:br]
        [:a {:href "/feed" :rel "alternate" :type "application/rss+xml" } "RSS"]
        [:script {:dangerouslySetInnerHTML {:__html script}}]]]))

(rum/defc email-sent-page [message]
  (page {}
    "Check your mail"
    [:div.email_sent_message message]))

(rum/defc forbidden-page [redirect-url]
  (page { :title "Entrance"}
    [:form { :action "/send-email" :method "post"}
      [:div.forbidden_email
        [:input { :type "text" :name "email" :placeholder "E-mail" :autofocus true}]]
      [:div
        [:input { :type "hidden" :name "redirect-url" :value redirect-url}]]
      [:div
        [:button.btn "Send a letter"]]]))

(defn safe-slurp [source]
  (try
    (slurp source)
    (catch Exception e
      nil)))


(defn get-post [post-id]
  (let [path (str "blog_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
      (safe-slurp)
      (edn/read-string))))


(rum/defc index-page [post-ids]
  (page { :index? true}
    (for [ post-id post-ids]
      (post (get-post post-id)))))


(rum/defc post-page [post-id]
  (page {}
    (post (get-post post-id))))


(rum/defc edit-post-page [post-id]
  (let [post (get-post post-id)
         create? (nil? post)]
    (page {:title (if create? "Creation" "Editing")}
      [:form { :action (str "/post/" post-id "/edit")
               :method "post"
               :enctype "multipart/form-data"}
        [:.edit_post_picture
          [:input { :type "file" :name "picture"}]]
        [:.edit_post_body
          [:textarea { :value (:body post "")
                       :placeholder "Write here..."
                       :name "body"
                       :autofocus true}]]
        [:.edit_post_submit
          [:button.btn (if create? "Create" "Save")]]])))


(defn post-ids []
  (->>
    (for [ name (seq (.list (io/file "posts")))
           :let [child (io/file "posts" name)]
           :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))

(defn with-headers [handler headers]
  (fn [request]
    (some->
      (handler request)
      (update :headers merge headers))))

(defn save-bytes! [file ^bytes bytes]
  (with-open [os (io/output-stream (io/file file))]
    (.write os bytes)))


(defn read-bytes [file len]
  (with-open [is (io/input-stream (io/file file))]
    (let [res (make-array Byte/TYPE len)]
      (.read is res 0 len)
      res)))

(defn random-bytes
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (java.security.SecureRandom.) seed)
    seed))

(when-not (.exists (io/file "blog_data/COOKIE_SECRET"))
  (save-bytes! "blog_data/COOKIE_SECRET" (random-bytes bytes)))

(def cookie-secret (read-bytes "blog_data/COOKIE_SECRET" 16))

(defn since [^Date inst]
  (- (.getTime (now)) (.getTime inst)))

(defn- get-token [email]
  (when-some [token (get @*tokens email)]
    (let [ created (:created token)]
      (when (<= (since created) token-ttl-ms)
        (:value token)))))

(compojure/defroutes routes

  (compojure.route/resources "/i" {:root "public/i"}) ;; renders files under the folder

  (compojure/GET "/" []
    { :body (render-html (index-page (post-ids)))})

  (compojure/GET "/post/:post-id/:img" [post-id img]
    (ring.util.response/file-response (str "blog_data/posts/" post-id "/" img)))

  (compojure/GET "/post/:post-id" [post-id]
    { :body (render-html (post-page post-id))})

  (compojure/GET "/authenticate" [:as req]
    (let [ email (get (:params req) "email")
           token (get (:params req) "token")
           user (get authors email)
           redirect-url (get (:params req) "redirect-url")]
      (if (= token (get-token email))
        (do
          (swap! *tokens dissoc email) ;;killing token after login to refresh
          (assoc
            (redirect redirect-url)
            :session { :user user
                       :creater (now)})
          { :status 403
            :body "403 Bad token"}))))
  
  (compojure/GET "/forbidden" [:as req]
    { :body (render-html (forbidden-page (get (:params req) "redirect-url")))})

  (compojure/GET "/logout" [:as req]
    (assoc
      (redirect "/")
      :session nil))

  (compojure/POST "/send-email" [:as req]
    (let [ params (:params req)
           email (get params "email")]
      (cond
        (not (contains? authors email))
        (redirect "/email-sent" { :message (str "You are not the author, " email)})
        (some? (get-token email))
        (redirect "/email-sent" { :message "Token is still alive, check your email"})
        :else
        (let [ token (gen-token)
               redirect-url (get params "redirect-url")
               link (str (name (:scheme req))
                      "://"
                      (:server-name req)
                      (when-not (= (:server-port req) 80)
                        (str ":" (:server-port req)))
                      "/authenticate"
                      "?email=" (encode-uri-component email)
                      "&token=" (encode-uri-component token)
                      "&redirect-url=" (encode-uri-component redirect-url))]
          (swap! *tokens assoc email { :value token :created (now)})
          (send-email!
            { :to      email
              :subject (str "Enter to Blog " (render-date (now)))
              :body    (str "<html><div style='text-align: center;'><a href='" link "' style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Enter to site!</a></div></html>")})
          (redirect "/email-sent" { :message (str "Check your mail, " email)})))))

  (compojure/GET "/email-sent" [:as req]
    { :body (render-html (email-sent-page (get-in req [:params "message"])))})

  (compojure/GET "/new" [:as req]
    (or
      (check-session req)
      (redirect (str "/post/" (next-post-id) "/edit"))))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (check-session req)
      { :body (render-html (edit-post-page post-id))}))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (check-session req)
        (let [ params (:multipart-params req)
               body (get params "body")
               picture (get params "picture")]
          (save-post! { :id post-id
                        :body body
                        :author (get-in req [:session :user])
                        :created (now)}
            [picture])
          (redirect "/")))))

  (fn [req]
    { :status 404
      :body "404 Page Not Found"}))



(defn- expire-session [handler]
  (fn [req]
    (let [created (:created (:session req))]
      (if (and (some? created)
            (> (since created) session-ttl))
        (handler (dissoc req :session))
        (handler req)))))


(def app
  (-> routes
    (expire-session)
    (session/wrap-session
      { :store (session.cookie/cookie-store { :key cookie-secret})
        :cookie-name "blog"
        :cookies-attrs { :http-only true
                         :secure false}})
    (ring.middleware.params/wrap-params)
    (with-headers { "Content-Type" "text/html; charset=utf-8"
                    "Cache-Control" "no-cache"
                    "Expires" "-1"})
    (print-errors)))


(defn start
  [& args]
  (let [ args-map (apply array-map args)
         port-str (or (get args-map "-p")
                    (get args-map "--port")
                    "8080")]
    (println "Starting server on port " port-str)
    (web/run #'app { :port (Integer/parseInt port-str)})))

(comment
  (def server (start "--port" "8080"))
  (web/stop server)
  (reset! *tokens {}))

