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
(defonce current-filename (atom nil))

(def re-todo-finder #"[\ \t]*\*[\ \t]*\[(.*?)\]")
(def re-todo-parser #"[\ \t]*\*[\ \t]*\[(.*?)\][\ \t]*(.*?)[\n$]([\s\S]*)")
(def re-only-spaces #"^[\s\t]*$")

;; -------------------------
;; Functions

;***** remove extension *****;

(defn no-extension [s] (.replace s ".txt" ""))

;***** todo parsing *****;

; http://stackoverflow.com/a/18737013/2131094
(defn re-pos [re s]
  "Find all the positions in a string s that a regular expression re matches."
  (let [re (js/RegExp. (.-source re) "g")]
    (loop [res {}]
      (if-let [m (.exec re s)]
        (recur (assoc res (.-index m) (first m)))
        res))))

(defn split-on-todos [todo-text]
  "Split up some text by positions of TODO list markers: * [ ] "
  (let [slice-positions (sort (conj
                                ; find the position of all todos within the source text
                                (vec (map #(first %) (re-pos re-todo-finder todo-text)))
                                ; add the complete text length as the final marker
                                (.-length todo-text)))]
    ; add zero as the initial marker if not present
    (if (= (first slice-positions) 0) slice-positions (into [0] slice-positions))))

(defn parse-todo-chunk [todo-chunk index]
  "Parse a chunk of text into a TODO list item: * [ ] My title... "
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
  "Turn a chunk of text into an array of TODO list state dictionaries."
  (when text
    (let [slice-positions (split-on-todos text)
          chunks (partition 2 1 slice-positions)
          todo-items (vec (map-indexed (fn [idx t]
                               (parse-todo-chunk (.substr text (first t) (- (last t) (first t))) idx))
                             chunks))]
      todo-items)))

(defn transform-text-todos [todo-text-items]
  "Given a hash-map of {:filename text :filename-2 text-2}
  replace the text items with their parsed TODO list state dictionaries."
  (into {} (map (fn [[fname todo-text]] [(no-extension fname) (extract-todos todo-text)]) todo-text-items)))

(defn reassemble-todos [todo-items]
  "Take an array of TODO list state dictionaries and then them back into text blob."
  (apply str (map
         #(if (% :matched)
            (str " * [" (if (% :checked) "x" " ") "] " (% :title) "\n" (% :details))
            (% :source))
         todo-items)))

;***** Network functions *****;

(defn get-files [timestamp]
  "Ask the server for a list of text files.
  Server blocks if none since timestamp.
  Returns a dictionary of :filename to text mappings."
  (let [c (chan)]
    (ajax-request {:uri server-url
                   :method :get
                   :params {:timestamp timestamp
                            :live_for (if (get-env :dev) 5 30)}
                   :with-credentials true
                   :response-format (json-response-format)
                   :handler #(put! c %)})
    c))

(defn update-file [fname text]
  "Ask the server to update a particular text file with text contents."
  (ajax-request {:uri server-url
                 :method :post
                 :format (url-request-format)
                 :params {:filename (str fname ".txt")
                          :content text}
                 :with-credentials true
                 :response-format (json-response-format)
                 ; TODO: handle result
                 :handler #(print "update-file result:" %)}))

(defn long-poller [todos instance-id]
  "Continuously poll the server updating the todos atom when the textfile data changes."
  (go (loop [last-timestamp 0]
          (print "Long poller initiated:" instance-id "timestamp:" last-timestamp)
          ; don't fire off more than 1 time per second
          (let [[ok result] (<! (get-files last-timestamp))]
            ; if we have fired off a new instance don't use this one
            (when (= instance-id @instance)
              (when (not ok)
                ; this happens with the poller timeout so we can't use it d'oh
                )
              (when (and ok (> (result "timestamp") last-timestamp))
                (print "long-poller result:" last-timestamp ok result)
                (reset! todos (transform-text-todos (result "files"))))
              (<! (timeout 1000))
              (recur (or (result "timestamp") last-timestamp)))))))

;***** event handlers *****;

(defn checkbox-handler [todos fname todo ev]
  "When the user clicks a checkbox, update the state."
  (swap! todos update-in [fname (todo :index) :checked] not)
  (update-file fname (reassemble-todos (@todos fname))))

;; -------------------------
;; Views

(defn todo-page []
  [:div 
   [:span.back {:on-click #(secretary/dispatch! "/")} "◀"]
   [:h3 @current-filename]
   (doall (let [todo-items (@todos @current-filename)]
            [:ul {}
             (map-indexed (fn [idx todo]
                            [:li {:key (todo :index) :class (str "oddeven-" (mod idx 2))}
                             [:span.handle "::"]
                             [:span.checkbox {:on-click (partial checkbox-handler todos @current-filename todo)} (if (todo :checked) "✔" "\u00A0")]
                             (todo :title)])
                           (filter :matched todo-items))]))])

(defn lists-page []
  [:div
   [:ul {}
    (map-indexed (fn [idx [filename todos]]
                   (let [fname (no-extension filename)]
                     [:li.todo-link {:key filename :class (str "oddeven-" (mod idx 2)) :on-click #(secretary/dispatch! (str "/todo/" fname))} fname])) @todos)]])

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes


(secretary/defroute "/" []
  (session/put! :current-page #'lists-page))

(secretary/defroute "/todo/:fname" [fname]
  (print fname)
  (reset! current-filename fname)
  (session/put! :current-page #'todo-page))

;; -------------------------
;; Initialize app

; initiate the long-poller
(long-poller todos (swap! instance inc))

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!)
  (accountant/dispatch-current!)
  (mount-root))
