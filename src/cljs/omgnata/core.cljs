(ns omgnata.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [ajax.core :refer [GET POST ajax-request json-response-format raw-response-format url-request-format]]
              [cljs.core.async :refer [<! chan close! put! timeout]])
    (:require-macros 
              [omgnata.env :refer [get-env]]
              [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(def server-url (if (get-env :dev) "http://localhost:8000/server.php" "server.php"))

(defonce instance (atom 0))
(defonce todos (atom {}))

(def re-todo-finder #"[\ \t]*\*[\ \t]*\[(.*?)\]")
(def re-todo-parser #"[\ \t]*\*[\ \t]*\[(.*?)\][\ \t]*(.*?)[\n$]([\s\S]*)")
(def re-only-spaces #"^[\s\t]*$")

;; -------------------------
;; Functions

(defn get-files [timestamp]
  (let [c (chan)]
    (ajax-request {:uri server-url
                   :method :get
                   :params {:timestamp timestamp}
                   :with-credentials true
                   :response-format (json-response-format)
                   :handler #(put! c %)})
    c))

(defn long-poller [instance-id]
  (go (loop [last-timestamp 0]
          (print "Long poller initiated:" instance-id)
          ; don't fire off more than 1 time per second
          (let [[ok result] (<! (get-files last-timestamp))]
            ; if we have fired off a new instance don't use this one
            (when (= instance-id @instance)
              (print "long-poller result:" ok result)
              (when ok
                (reset! todos (result "files")))
              (<! (timeout 1000))
              (recur (result "timestamp")))))))

; http://stackoverflow.com/a/18737013/2131094
(defn re-pos [re s]
  (let [re (js/RegExp. (.-source re) "g")]
    (loop [res {}]
      (if-let [m (.exec re s)]
        (recur (assoc res (.-index m) (first m)))
        res))))

(defn split-on-todos [todo-text]
  (let [slice-positions (sort (conj
                                ; find the position of all todos within the source text
                                (vec (map #(first %) (re-pos re-todo-finder todo-text)))
                                ; add the complete text length as the final marker
                                (.-length todo-text)))]
    ; add zero as the initial marker if not present
    (if (= (first slice-positions) 0) slice-positions (into [0] slice-positions))))

(defn parse-todo-chunk [todo-chunk index]
  (let [[matched checked title details] (.exec (js/RegExp. re-todo-parser) todo-chunk)]
    (if matched
      {:matched true
       :checked (nil? (.exec (js/RegExp. re-only-spaces) checked))
       :title title
       :details details
       :source todo-chunk
       :index index}
      {:matched false
       :source todo-chunk
       :index index})))

(defn extract-todos [text]
  (when text
    (let [slice-positions (split-on-todos text)
          chunks (partition 2 1 slice-positions)
          todos (map-indexed (fn [idx t]
                               (parse-todo-chunk (.substr text (first t) (- (last t) (first t))) idx))
                             chunks)]
      todos)))

(defn reassemble-todos [todos]
  (apply str (map
         #(if (% :matched)
            (str " * [" (if (% :checked) "x" " ") "] " (% :title) "\n" (% :details))
            (% :source))
         todos)))

;; -------------------------
;; Views

(defn home-page []
  [:div
   (doall (for [[fname text] @todos]
            [:ul {:key fname}
             [:li {} [:h3 fname]]
             (map-indexed (fn [idx todo]
                            [:li {:key (todo :index) :class (str "oddeven-" (mod idx 2))} [:span.handle "::"] [:span.checkbox {} (if (todo :checked) "✔" " ")] (todo :title)])
                           (filter :matched (extract-todos text)))]))])

(defn about-page []
  [:div [:h2 "About omgnata"]
   [:div [:a {:href "/"} "go to the home page"]]])

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

(secretary/defroute "/about" []
  (session/put! :current-page #'about-page))

;; -------------------------
;; Initialize app

; initiate the long-poller
(long-poller (swap! instance inc))

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!)
  (accountant/dispatch-current!)
  (mount-root))
