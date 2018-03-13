(ns blog.server
  (:require
    [compojure.route]
    [rum.core :as rum]
    [clojure.stacktrace]
    [ring.util.response]
    [immutant.web :as web]
    [clojure.string :as str]
    [ring.middleware.params]
    [compojure.core :as compojure]

    [blog.core :as blog]
    [blog.auth :as auth]
    [blog.authors :as authors]))
    


(set! *warn-on-reflection* true)

(defn print-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (.printStackTrace e)
        { :status 500
          :headers { "Content-type" "text/plain; charset=utf-8"} 
          :body (with-out-str
                  (clojure.stacktrace/print-stack-trace (clojure.stacktrace/root-cause e)))}))))


(rum/defc post [post]
  [:.post
    [:.post_sidebar
      [:img.post_avatar {:src (str "/static/" (:author post) ".gif")}]]
    [:.post_body
      (for [name (:pictures post)]
        [:img.post_img { :src (str "/post/" (:id post) "/" name)}])
      (for [[p idx] (blog/zip (str/split (:body post) #"\n+") (range))]
        [:p.post_p
          (when (== 0 idx)
            [:span.post_author (:author post) ": "])
          p])
      [:p.post_meta (blog/render-date (:created post))
        "//" [:a {:href (str "/post/" (:id post))} "Link"]
        [:span.post_meta_edit " × " [:a {:href (str "/post/" (:id post) "/edit")} "Edit"]]]]])


(defn with-headers [handler headers]
  (fn [request]
    (some->
      (handler request)
      (update :headers merge headers))))


(rum/defc index-page [post-ids]
  (blog/page { :index? true}
    (for [ post-id post-ids]
      (post (blog/get-post post-id)))))


(rum/defc post-page [post-id]
  (blog/page {}
    (post (blog/get-post post-id))))


(compojure/defroutes routes

  (compojure/GET "/" []
    (blog/html-response (index-page (blog/post-ids))))

  (compojure/GET "/post/:post-id/:img" [post-id img]
    (ring.util.response/file-response (str "blog_data/posts/" post-id "/" img)))

  (compojure/GET "/post/:post-id" [post-id]
    (blog/html-response (post-page post-id)))

  (auth/wrap-session
    (compojure/routes
      auth/routes
      authors/routes)))


(def app
  (compojure/routes
    (->
      routes
      (ring.middleware.params/wrap-params)
      (with-headers { "Content-Type" "text/html; charset=utf-8"
                      "Cache-Control" "no-cache"
                      "Expires" "-1"})
      (print-errors))
    (->
      (compojure.route/resources "/static" {:root "static"})
      (with-headers { "Cache-Control" "no-cache"
                      "Expires" "-1"}))
    (fn [req]
      { :status 404
        :body "404 Not Found"})))


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

