(ns authors
  (:require
    [rum.core :as rum]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.middleware.multipart-params]
    [blog.core :as blog]
    [blog.auth :as auth]))

(defn next-post-id []
  (str
    (blog/encode (quot (System/currentTimeMillis) 1000) 6)
    (blog/encode (rand-int (* 64 64 64)) 3)))


(defn save-post! [post pictures]
  (let [dir           (io/file (str "blog_data/posts/" (:id post)))
         picture-names (for [[picture idx] (blog/zip pictures (range))
                              :let [in-name  (:filename picture)
                                     [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                         (str (:id post) "_" (inc idx) ext))]
    (.mkdirs dir)
    (doseq [[picture name] (blog/zip pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (.delete (:tempfile picture)))
    (spit (io/file dir "post.edn") (pr-str (assoc post :pictures (vec picture-names))))))


(rum/defc edit-post-page [post-id]
  (let [post (blog/get-post post-id)
         create? (nil? post)]
    (blog/page { :title (if create? "Creation" "Editing")
                 :styles "authors.css"}
      [:form.edit-post
        { :action (str "/post/" post-id "/edit")
          :method "post"
          :enctype "multipart/form-data"}
        [:.form_row.edit-post_picture
          [:input { :type "file" :name "picture"}]]
        [:.form_row
          [:textarea { :value (:body post "")
                       :placeholder "Write here..."
                       :name "body"
                       :autofocus true}]]
        [:.form_row
          [:button (if create? "Create" "Save")]]])))


(compojure/defroutes routes
  (compojure/GET "/new" [:as req]
    (or
      (auth/check-session req)
      (blog/redirect (str "/post/" (next-post-id) "/edit") {})))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (auth/check-session req)
      (blog/render-html (edit-post-page post-id))))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (blog/check-session req)
        (let [ params (:multipart-params req)
               body (get params "body")
               picture (get params "picture")]
          (save-post! { :id post-id
                        :body body
                        :author (get-in req [:session :user])
                        :created (now)}
            [picture])
          (blog/redirect "/" {}))))))

