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

(def page-size 5)

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
    { :data-id (:id post)}
    [:.post_side
      [:img.post_avatar {:src (str "/static/" (:author post) ".gif")}]]
    [:.post_body
      (for [ name (:pictures post)
             :let [src (str "/post/" (:id post) "/" name)]]
        (if (str/ends-with? name ".mp4")
          [:video.post_img { :autoplay true :loop true}
            [:source { :type "video/mp4" :src src}]]
          [:img.post_img { :src src}]))
      (for [[p idx] (blog/zip (str/split (:body post) #"(\r?\n)+") (range))]
        [:p.post_p
          (when (== 0 idx)
            [:span.post_author (:author post) ": "])
          p])
      [:p.post_meta (blog/format-date (:created post))
        "//" [:a {:href (str "/post/" (:id post))} "Link"]
        [:span.post_meta_edit " × " [:a {:href (str "/post/" (:id post) "/edit")} "Edit"]]]]])


(defn with-headers [handler headers]
  (fn [request]
    (some->
      (handler request)
      (update :headers merge headers))))


(rum/defc index-page [post-ids]
  (blog/page { :index? true :scripts ["loader.js"]}
    (for [ post-id post-ids]
      (post (blog/get-post post-id)))))


(rum/defc post-page [post-id]
  (blog/page {}
    (post (blog/get-post post-id))))

(rum/defc posts-fragment [post-ids]
  (for [post-id post-ids]
    (post (blog/get-post post-id))))


(compojure/defroutes routes

  (compojure/GET "/" []
    (let [ post-ids  (blog/post-ids)
           first-ids (take (+ page-size (rem (count post-ids) page-size)) post-ids)]
      (blog/html-response (index-page first-ids))))

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "blog_data/posts/" id "/" img)))    

  (compojure/GET "/post/:post-id" [post-id]
    (blog/html-response (post-page post-id)))

  (compojure/GET "/after/:post-id" [post-id]
    (let [post-ids (->> (blog/post-ids)
                     (drop-while #(not= % post-id))
                     (drop 1)
                     (take page-size))]
      { :status  200
        :headers { "Content-Type" "text/html; charset=utf-8"}
        :body    (rum/render-static-markup (posts-fragment post-ids))}))

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

