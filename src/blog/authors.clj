(ns blog.authors
  (:require
    [rum.core :as rum]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.middleware.multipart-params]
    
    [blog.core :as blog]
    [blog.auth :as auth]))


(defn next-post-id [^java.util.Date inst]
  (str
    (blog/encode (quot (.getTime inst) 1000) 6)
    (blog/encode (rand-int (* 64 64 64)) 3)))


(defn save-post! [post pictures {:keys [delete?]}]
  (let [ id (:id post)
         old-post (blog/get-post id)
         dir (io/file (str "blog_data/posts/" (:id post)))
         picture-names (for [[picture idx] (blog/zip pictures (range))
                              :let [in-name  (:filename picture)
                                     [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                         (str id "_" (inc idx) ext))]
    (if (nil? old-post)
      (.mkdirs dir)
      (when (not-empty picture-names)
        (doseq [name (:pictures old-post)]
          (io/delete-file (io/file dir name)))))
    (doseq [[picture name] (blog/zip pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (when delete?
        (.delete (:tempfile picture))))
    (let [ post' (merge
                   { :pictures (or (not-empty (vec picture-names))
                                 (:pictures old-post))
                     :created (blog/now)
                     :updated (blog/now)}
                   (select-keys old-post [:created]))]
      (spit (io/file dir "post.edn") (pr-str post')))))


(rum/defc edit-post-page [post-id]
  (let [ post    (blog/get-post post-id)
         create? (nil? post)]
    (blog/page { :title (if create? "New post" "Edit post")
                 :styles ["authors.css"]}
      [:form.edit-post
        { :action (str "/post/" post-id "/edit")
          :enctype "multipart/form-data"
          :method "post"}
        [:.form_row.edit-post_picture
          [:input { :type "file" :name "picture"}]]
        [:.form_row
          [:textarea
            { :value (:body post "")
              :name "body"
              :placeholder "Write here..."
              :autofocus true}]]
        [:.form_row
          [:button (if create? "Print!" "Edit")]]]
      [:script { :src "/static/editor.js"}])))


(compojure/defroutes routes
  (compojure/GET "/new" [:as req]
    (or
      (auth/check-session req)
      (blog/redirect (str "/post/" (next-post-id (blog/now)) "/edit") {})))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (auth/check-session req)
      (blog/html-response (edit-post-page post-id (auth/user req)))))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (auth/check-session req)
        (let [ params  (:multipart-params req)
               body    (get params "body")
               picture (get params "picture")]
          (save-post! { :id      post-id
                        :body    body
                        :author  (get-in req [:session :user])}
            (when (some-> picture :size pos?)
              [picture])
            { :delete? true})
          (blog/redirect "/"))))))
