(ns blog.editor
  (:require
    [rum.core :as rum]))

(rum/defc edit-post-page [post user]
  (let [create? (nil? post)]
    [:form.edit-post
      { :action (str "/post/" (:id post) "/edit")
        :enctype "multipart/form-data"
        :method "post"}
      [:.form_row.edit-post_picture
        [:input { :type "file" :name "picture"}]]
      [:.form_row
        [:textarea
          { :value (:body post "")
            :name "body"
            :placeholder "Blog here..."
            :autofocus true}]]
      [:.form_row
        "Author: " [:input.edit-post_author { :type "text" :name "author" :value (or (:author post) user)}]]
      [:.form_row
        [:button (if create? "Blog post now!" "Edit")]]]))
      
(enable-console-print!)

(defn ^:export refresh []
  (rum/mount (edit-post-page nil nil) (js/document.querySelector "#mount")))
