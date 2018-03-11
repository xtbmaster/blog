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
    [org.joda.time.format DateTimeFormat]))

(def date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))

(def styles (slurp (io/resource "static/styles.css")))
(def script (slurp (io/resource "static/scripts.js")))

(defn render-date [inst]
  (.print date-formatter (DateTime. inst)))

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

(defn zip [coll1 coll2] ;; TODO see map-indexed
  (map vector coll1 coll2))

(defn next-post-id []
  (str (encode (quot (System/currentTimeMillis) 1000) 6)
    (encode (rand-int (* 64 64 64)) 3)))

(defn save-post! [post pictures]
  (let [ dir           (io/file (str "posts/" (:id post)))
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

(defn- read-session [handler]
  (fn [req]
    (let [ session (some-> (get-in req [:cookies "session" :value])
                     (edn/read-string))]
      (handler (if (some? session)
                 (assoc req :user (:user session))
                 req)))))

(defn check-session [req]
  (when (nil? (:user req))
    { :status 302
      :headers { "Location" (str "/forbidden?redirect=" (encode-uri-component (:uri req)))}}))

(rum/defc forbidden-page [redirect]
  (page []
    [:a {:href (str "/authenticate?user=aaliiev&token=ABC&redirect=" (encode-uri-component redirect))}
      "Login"]))

(compojure/defroutes protected-routes
  (compojure/GET "/write" [:as req]
    (or
      (check-session req)
      { :status 303
        :headers { "Location" (str "/post/" (next-post-id) "/edit")}}))

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
                        :author (:user req)
                        :created (java.util.Date)}
            [picture])
          { :status 302
            :headers { "Location" (str "/post/" post-id)}})))))

(compojure/defroutes routes

  (compojure.route/resources "/i" {:root "public/i"}) ;; renders files under the folder

  (compojure/GET "/" []
    { :body (render-html (index-page (post-ids)))})


  (compojure/GET "/post/:post-id/:img" [post-id img]
    (ring.util.response/file-response (str "posts/" post-id "/" img)))

  (compojure/GET "/post/:post-id" [post-id]
    { :body (render-html (post-page post-id))})

  (compojure/GET "/authenticate" [:as req]
    (let [ user (get (:params req) "user")
           token (get (:params req) "token")
           redirect (get (:params req) "redirect")]
      { :status 302
        :headers { "Location" redirect}
        :cookies { "session" { :value (pr-str { :user user})
                               :http-only true
                               :secure false}}}))

  (compojure/GET "/forbidden" [:as req]
    { :body (render-html (forbidden-page (get (:params req) "redirect")))})

  protected-routes

  (fn [req]
    { :status 404
      :body "404 Page Not Found"})

  (defn encode-uri-component [s]
    (-> s
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))



  (defn print-errors [handler]
    (fn [req]
      (try
        (handler req)
        (catch Exception e
          { :status 500
            :headers { "Content-Type" "text/html; charset=utf-8"} 
            :body (with-out-str
                    (clojure.stacktrace/print-stack-trace (clojure.stacktrace/root-cause e)))})))))



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
          :or {title "Pack"
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


(defn safe-slurp [source]
  (try
    (slurp source)
    (catch Exception e
      nil)))


(defn get-post [post-id]
  (let [path (str "posts/" post-id "/post.edn")]
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

(defn random-bytes
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (java.security.SecureRandom.) seed)
    seed))
  
(def app
  (-> routes
    (read-session)
    (session/wrap-session)
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
  (web/stop server))

