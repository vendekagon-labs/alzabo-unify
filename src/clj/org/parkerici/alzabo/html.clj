(ns org.parkerici.alzabo.html
  (:require [org.parkerici.alzabo.schema :as schema]
            [org.parkerici.alzabo.config :as config]
            [org.parkerici.multitool.core :as u]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute])
  (:use [hiccup.core]))

(defn- kind-url
  [kind]
  (str (name kind) ".html"))

(defn- kind-link
  [kind]
  (html [:a {:href (kind-url kind)}
         kind]))

(defn- kind-html
  [kind]
  (if (keyword? kind)
    (if (get schema/primitives kind)
      (name kind)
      (kind-link kind))
    ;; tuple
    [:span "["
     (interpose
      " "
      (for [k kind]
        (kind-html k)))
     "]"]))

(def primitives #{:long :float :string :boolean :instant :keyword}) ; :ref

(defn- linkify
  [s]
  (when s 
    (s/replace s #"(http(s|)\:\S*)" "<a href=\"$1\">$1</a>")))

;;; can't believe this isn't built into hiccup
(defn- style-arg
  [m]
  (s/join (map (fn [[p v]] (format "%s: %s;" (name p) v)) m)))

(defn- field->html
  [field props]
  (html
   [:tr
    ;; TODO getting to the point where columns should be data-driven
    [:td {:style (style-arg {:white-space "nowrap"})} field]
    [:td (kind-html (:type props))]
    [:td (:cardinality props)]
    [:td (:unique props)]
    [:td (linkify (:doc props))]]))

(defn- backlink
  []
  [:a {:href "index.html"} "← schema"])

;;; Schema is actually just the kinds structure
(defn- kind->html
  [kind schema]
  (let [kind-schema (get schema kind)
        unique-id (get kind-schema :unique-id)
        label (get kind-schema :label)]
    (html
     (backlink)
     [:h1 (name kind)]
     [:table {:class "table"}
      [:tr
       [:th "attribute"]
       [:th "type"]
       [:th "cardinality"]
       [:th "unique"]
       [:th "description"]]
      (for [[field props] (into (sorted-map) (get kind-schema :fields ))]
        (field->html field props))]
     (when unique-id
       [:div
        [:b "Unique ID"] ": " (name unique-id)])
     (when label
       [:div
        [:b "Label"] ": " (name label)])
     )))

(defn- enum->html
  [enum {:keys [values doc]}]
  (html
   (backlink)
   [:h1 (name enum)]
   [:span doc]
   [:table {:class "table"}
    [:tr [:th "values"] [:th "doc"]]
    (for [[v doc] values]
      (html [:tr [:td v] [:td doc]]))]))

(defn- clear-directory
  [d]
  (fs/delete-dir d LinkOption/NOFOLLOW_LINKS)
  (fs/mkdirs d))

(defn- output-file
  [file]
  (config/output-path file))

(defn- html-out
  [file title the-html version]
  (spit (output-file file)
        (html
         ;; should be a template I suppose but this was faster
         [:html
          [:head
           [:title title]
           [:meta {:charset "UTF-8"}]   ;TODO was UTF-16, which broke client.js...why was it that way?
           [:link {:href "https://fonts.googleapis.com/css?family=Lato:400,700"
                   :rel "stylesheet"}]
           [:link {:rel "stylesheet"
                   :href "https://stackpath.bootstrapcdn.com/bootstrap/4.1.3/css/bootstrap.min.css"
                   :integrity "sha384-MCw98/SFnGE8fJT3GXwEOngsV7Zt27NXFoaoApmYm81iuXoPkFOJwJ8ERdknLPMO"
                   :crossorigin "anonymous"}]
           [:link {:rel "stylesheet"
                   :href "alzabo.css"}]]
          [:body
           [:div {:class "container"}
            the-html]]])))



;; yes this should be in css.
(defn- header-style
  [color]
  (style-arg
   {:background color
    :display "inline"
    :padding-left "6px"
    :padding-right "6px"}))

(defn- index->html
  [{:keys [kinds enums version title] :as schema} tag-version]
  (let [[nonreference-kinds reference-kinds]
        (u/separate #(nil? (get-in kinds [% :reference?])) (keys kinds))]
    (html
     [:h1 title " Schema " version]
     [:div#app]
     [:div.container
      [:div.row
       [:div.py-5
        [:img {:src "schema.dot.svg"
               :usemap "#schema"}]
        (slurp (output-file "schema.dot.cmapx")) 
        ]]]
     [:div.container
      [:div.row
       [:div.col
        [:h2 "Kinds"]
        ;;; OK ugly – when there is this split, add headers and separate section
        (when (config/config :reference?)
          [:div
           [:h3 {:style (header-style (config/config :reference-color))}
            "reference"]
           [:ul
            (for [kind (sort reference-kinds)]
              (html [:li (kind-html kind)]))]
           [:h3 {:style (header-style (config/config :main-color))} "experimental"]
           ])
        [:ul
         (for [kind (sort nonreference-kinds)]
           (html [:li (kind-html kind)]))
         ]]
       [:div.col
        [:h2 "Enums"]
        [:ul
         (for [enum (sort (keys enums))]
           (html [:li (kind-html enum)]))]]
       ;; Pass the schema to clojurescript widget inside an invisible div
       ;; See org.parkerici.alzabo.search.core/get-schema
       [:div#aschema {:style (style-arg {:display "none"})}
        (str schema)]
       [:script {:src "js/client.js"}]
       [:script "window.onload = function() { org.parkerici.alzabo.search.core.run(); }"]
       ]]
     )))

(defn- kind-relations
  "This generates the list of edges in the graph. Given a kind, returns a list of [relation kind cardinality] pairs. Expands tuples"
  [kind {:keys [kinds enums]}]
  (filter (fn [[_ type _]]
            (get kinds type))
          (mapcat (fn [[fname {:keys [cardinality type] :as field-def}]]
                    (if (vector? type)  ; handle tuple types
                      (map (fn [tuple-component idx]
                             [(keyword (str (name fname) idx)) tuple-component cardinality])
                           type (range))
                      (list [fname type cardinality])))
                  (get-in kinds [kind :fields]))))

(defn- sh-errchecked
  [& args]
  (let [res (apply shell/sh args)]
    (when-not (= (:exit res) 0)
      (throw (Exception. (str "Bad result from shell" res))))
    res))

;;; To use this, do 'brew install graphviz' first (on Mac)
(def dot-command "dot")

;;; Font should be available in Docker build image
(def graph-font "Helvetica")

;;; TODO if this gets any more complex, consider replacing with https://github.com/daveray/dorothy
(defn- write-graphviz
  [{:keys [kinds enums] :as schema} dot-file]
  (let [clean (fn [kind] (s/replace (name kind) \- \_))
        attributes (fn [m & [sep]]
                 ;; For some reason graph attributes need a different separator
                 (s/join (or sep ",") (map (fn [[k v]] (format "%s=%s" (name k) (pr-str v))) m)))]
    (println "Writing " dot-file)
    (with-open [wrtr (clojure.java.io/writer dot-file)]
      (binding [*out* wrtr]
        (println "digraph schema {")
        (println (attributes
                  {:rankdir "LR"
                   :size "14,20"}
                  ";"))
        (doseq [kind (keys kinds)]
          (println (format "%s [%s];"
                           (clean kind)
                           (attributes {:URL (kind-url kind)
                                        :label (name kind)
                                        :style "filled"
                                        :fillcolor (if (get-in kinds [kind :reference?])
                                                     (config/config :reference-color)
                                                     (config/config :main-color))
                                        :fontname graph-font})))
          (doseq [[label ref cardinality] (kind-relations kind schema)]
            (println (format "%s -> %s [%s];"
                             (clean kind)
                             (clean ref)
                             (attributes
                              (merge
                               {:arrowhead (if (= cardinality :many) "diamond" "normal")} ;"crow" is better semantically but looks bad on ovals.
                               (when (config/config :edge-labels?)
                                 {:fontname graph-font
                                  :label (name label)})))
                             ))))
        (println "}"))
      (println "Generating .svg")
      (sh-errchecked
       dot-command
       dot-file
       "-Tsvg"
       "-O"
       "-Tcmapx"
       "-O"
       )
      )))

;;; the fs/ symlink command won't do the right thing
(defn- make-link
  [path target]
  (Files/createSymbolicLink
   (.toPath (io/file path))
   (.toPath (io/file target))
   (make-array FileAttribute 0)))

(defn schema->html
  "Generate HTML docs for a schema, including .svg and related files. Arguments are self-explanatory."
  [{:keys [kinds enums version title] :as schema} ]

    (clear-directory (output-file ""))
    ;; Write out the schema itself – used by autocomplete, enflame, etc
    (with-open [o (clojure.java.io/writer (output-file "schema.edn"))]
      (pp/pprint schema o))
    (doseq [kind (keys kinds)]
      (html-out (str (name kind) ".html")
                (format "%s - %s Schema" (name kind) title)
                (kind->html kind kinds)
                version))
    (doseq [enum (keys enums)]
      (html-out (str (name enum) ".html")
                (format "%s - Schema" (name enum) title)
                (enum->html enum (get enums enum))
                version))
    (write-graphviz schema (output-file "schema.dot"))
    (html-out "index.html"
              (format "%s - Schema - index" title)
              (index->html schema version)
              version)
    (make-link (output-file "js") "../../js") ;argh. Necessary apparently, net infrastructure no longer lets .. work
    (make-link (output-file "alzabo.css") "../../alzabo.css")
    nil
    )

;;; TODO class descriptions (Should be added to model in pret)

