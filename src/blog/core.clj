(ns blog.core
  (:refer-clojure :exclude [slurp])
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io])
  (:import
    [java.util Date]
    [java.net URLEncoder]
    [org.joda.time DateTime]
    [org.joda.time.format DateTimeFormat DateTimeFormatter]))


(.mkdirs (io/file "blog_data"))


(defmacro from-config [name default-value]
  `(let [file# (io/file "blog_data" ~name)]
     (when-not (.exists file#)
       (spit file# ~default-value))
     (clojure.core/slurp file#)))

(def authors (edn/read-string
               (from-config "AUTHORS"
                 (pr-str #{ {:email "arturaliiev@gmail.com" :user "arthur" :name "Arthur Aliiev"}}))))

(defn author-by [attr value]
  (first (filter #(= (get % attr) value) authors)))

;; (def hostname (from-config "HOSTNAME" "http://blog.site"))
(def hostname (from-config "HOSTNAME" "http://localhost:8080"))


(def dev? (= "http://localhost:8080" hostname))


(defn zip [coll1 coll2] ;; TODO see map-indexed
  (map vector coll1 coll2))


(defn now ^Date []
  (Date.))


(defn age [^Date inst]
  (- (.getTime (now)) (.getTime inst)))


(def ^:private date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))

(defn format-date [^Date inst]
  (.print ^DateTimeFormatter date-formatter (DateTime. inst)))


(defn encode-uri-component [s]
  (-> s
    (java.net.URLEncoder/encode "UTF-8")
    (str/replace #"\+"   "%20")
    (str/replace #"\%21" "!")
    (str/replace #"\%27" "'")
    (str/replace #"\%28" "(")
    (str/replace #"\%29" ")")
    (str/replace #"\%7E" "~")))


(defn url [path query]
  (str
    path
    "?"
    (str/join "&"
      (map
        (fn [[k v]]
          (str (name k) "=" (encode-uri-component v)))
        query))))


(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")

(defn encode [num len]
  (loop [ num  num
          list ()
          len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
        (let [ch (nth encode-table (bit-and num 0x3F))]
          (conj list ch))
        (dec len)))))



(defn redirect
  ([url] { :status 302
           :headers { "Location" url}})
  ([url query] (let [ query-str (map
                                 (fn [[k v]]
                                   (str (name k) "=" (encode-uri-component v)))
                                 query)]
                 { :status 302
                   :headers { "Location" (str url "?" (str/join "&" query-str))}})))


(defn slurp [source]
  (try
    (clojure.core/slurp source)
    (catch Exception e
      nil)))


(defn get-post [post-id]
  (let [path (str "blog_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
      slurp
      (edn/read-string))))

(defn list-files
  ([dir] (seq (.list (io/file dir))))
  ([dir re] (seq (.list (io/file dir)
                (proxy [java.io.FilenameFilter] []
                  (accept ^boolean [^java.io.File file ^String name]
                    (boolean (re-matches re name))))))))

(defn post-ids []
  (->>
    (for [ name (list-files "blog_data/posts")
           :let [child (io/file "blog_data/posts" name)]
           :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))



(def resource
  (cond-> (fn [name]
            (clojure.core/slurp (io/resource (str "static/" name))))
    (not dev?)
    (memoize)))


(rum/defc page [opts & children]
  (let [{ :keys [title index? styles scripts]
          :or { title  "Blog"
                index? false}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:link { :href "/static/favicons/loader.png" :rel "icon" :sizes "196x196"}]
        [:link { :href "/static/favicons/loader.png"  :rel "icon" :sizes "32x32"}]

        [:title title]
        [:style { :type "text/css" :dangerouslySetInnerHTML { :__html (resource "styles.css")}}]
        (for [css styles]
          [:style { :type "text/css" :dangerouslySetInnerHTML { :__html (resource css)}}])]
      [:body.anonymous
        [:header
          (if index?
            [:h1.title title [:a.title_new { :href "/new" } "+"]]
            [:h1.title [:a.title_back {:href "/"} "◄"] title])
          [:p.subtitle [:span " "]]]
        children
        (when index?
          [:.loader "..."])
        [:footer
          [:a {:href "https://github.com/xtbmaster"} "Arthur Aliiev"]
          ". 2018. All rights aren't reserved. Inspired by Nikita Prokopov's 'grumpy.website'."]
        [:script {:dangerouslySetInnerHTML { :__html (resource "scripts.js")}}]
        (for [script scripts]
          [:script {:dangerouslySetInnerHTML { :__html (resource script)}}])]]))


(defn html-response [component]
  { :status 200
    :headers { "Content-Type" "text/html; charset=utf-8"}
    :body (str "<!DOCTYPE html>\n" (rum/render-static-markup component))})





