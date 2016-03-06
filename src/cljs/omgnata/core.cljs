(ns omgnata.core
    (:require [reagent.core :as reagent :refer [atom dom-node]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [ajax.core :refer [GET POST ajax-request json-response-format raw-response-format url-request-format]]
              [cljs.core.async :refer [<! chan close! put! timeout]])
    (:require-macros 
              [omgnata.env :refer [get-env]]
              [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(def server-url (if (get-env :dev) (str (.replace (-> js/document .-location .-href) ":3449" ":8000") "server.php") "server.php"))

(defonce instance (atom 0))
(defonce todo-lists (atom {}))

(def re-todo-finder #"[\ \t]*\*[\ \t]*\[(.*?)\]")
(def re-todo-parser #"[\ \t]*\*[\ \t]*\[(.*?)\][\ \t]*(.*?)[\n$]([\s\S]*)")
(def re-only-spaces #"^[\s\t]*$")

;; -------------------------
;; Functions

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

(defn re-compute-indices [todo-items]
  (into {} (for [[fname todo-list] todo-items]
             [fname (vec (map-indexed (fn [idx t] (assoc t :index idx)) todo-list))])))

(defn remove-item [todo-items fname todo]
  (update-in todo-items [fname] (fn [todo-list] (remove #(= (% :index) (todo :index)) todo-list))))

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
              (let [transformed-todos (transform-text-todos (result "files"))]
                (when (and ok (not (= @todos transformed-todos)) (> (result "timestamp") last-timestamp))
                  (print "long-poller result:" last-timestamp ok result)
                  (reset! todos transformed-todos)))
              (<! (timeout 1000))
              (recur (or (result "timestamp") last-timestamp)))))))

;***** event handlers *****;

(defn checkbox-handler [todos fname todo ev]
  "When the user clicks a checkbox, update the state."
  (let [todo-list (@todos fname)]
    (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                              (update-in [fname (todo :index) :checked] not)
                              (re-compute-indices)))
                          fname)))))

(defn delete-item-handler [todos fname todo ev]
  (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                              (remove-item fname todo)
                              (re-compute-indices)))
                          fname))))

(defn update-item-handler [todos fname todo item-title ev]
  (let [todo-list (@todos fname)]
    (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                              (assoc-in [fname (todo :index) :title] @item-title)
                              (re-compute-indices)))
                          fname)))))

(defn add-todo-item-handler [todos fname new-item-title add-mode ev]
  (update-file fname (reassemble-todos
                       ((swap! todos 
                               #(-> %
                                    (update-in [fname] (fn [todo-list new-item] (into [new-item] todo-list)) {:title @new-item-title :checked false :matched true})
                                    (re-compute-indices)))
                        fname)))
  (reset! new-item-title "") 
  (swap! add-mode not))

;; -------------------------
;; Views

(defn component-input [item-title edit-mode]
    [:textarea.edit-item-text {:value @item-title
                               :on-change #(reset! item-title (-> % .-target .-value))
                               :on-blur (fn [ev] 
                                          ; (js/console.log (.-target ev))
                                          ; (swap! edit-mode not)
                                          ; Ugh - hack
                                          )}])

(def component-input-with-focus
  (with-meta component-input
             {:component-did-mount
              (fn [this]
                (let [node (dom-node this)
                      pos (.-length (.-value node))]
                  ; focus on the textbox
                  (.focus node)
                  ; put the cursor at the end
                  (.setSelectionRange node pos pos)))}))

(defn component-todo-item [filename todo]
  (let [edit-mode (atom false)
        item-title (atom (todo :title))]
    (fn [todos idx todo]
      [:li.todo-line {:key (todo :index) :class (str "oddeven-" (mod idx 2))}
       (if @edit-mode
         [:span.edit-mode {}
          [component-input-with-focus item-title edit-mode]
          [:span.btn.update-item-done {:on-click (partial update-item-handler todos filename todo item-title) :class "fa fa-check-circle"}] 
          [:span.btn.delete-item {:on-click (partial delete-item-handler todos filename todo) :class "fa fa-trash"}]]
         [:span {}
          [:span.handle.btn {:class "fa fa-sort"}] 
          [:span.checkbox.btn {:on-click (partial checkbox-handler todos filename todo) :class (if (todo :checked) "fa fa-check-circle" "fa fa-circle")}] 
          [:div.todo-text {:on-double-click #(swap! edit-mode not)} (todo :title)]])])))

(defn todo-page [todos filename]
  (let [add-mode (atom false)
        new-item-title (atom "")]
    (fn []
      [:div.todo-page
       [:span#back.btn {:on-click #(secretary/dispatch! "/") :class "fa fa-chevron-circle-left"}]
       [:h3.list-title filename]
       [:span#add-item.btn {:on-click #(swap! add-mode not) :class (if @add-mode "fa fa-times-circle" "fa fa-plus-circle")}]
       [:span#clear-completed.btn {:class "fa fa-trash"}]
       (when @add-mode
         [:div#add-item-container
          [:textarea.add-item-text {:on-change #(reset! new-item-title (-> % .-target .-value)) :value @new-item-title}]
          [:span#add-item-done.btn {:on-click (partial add-todo-item-handler todos filename new-item-title add-mode) :class "fa fa-check-circle"}]])
       [:ul {:key filename}
        (doall (map-indexed (fn [idx todo] ^{:key (todo :index)} [(partial component-todo-item filename todo) todos idx todo])
                            (filter :matched (@todos filename))))]])))

(defn lists-page [todos]
  (let [add-mode (atom false)
        new-item (atom "")]
    (fn []
      [:div
       [:span#add-list.btn {:on-click #(swap! add-mode not) :class (if @add-mode "fa fa-times-circle" "fa fa-plus-circle")}]
       (when @add-mode
         [:div#add-item-container
          [:input {:on-change #(reset! new-item (-> % .-target .-value)) :value @new-item}]
          [:span#add-item-done.btn {:class "fa fa-check-circle"}]])
       [:ul {}
        (doall (map-indexed (fn [idx [filename todo-list]]
                              (let [fname (no-extension filename)]
                                [:li.todo-link {:key filename :class (str "oddeven-" (mod idx 2)) :on-click #(secretary/dispatch! (str "/todo/" fname))}
                                 (if @add-mode [:span.delete-list.btn {:class "fa fa-trash"}])
                                 fname])) @todos))]])))

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes


(secretary/defroute "/" []
  (session/put! :current-page (lists-page todo-lists)))

(secretary/defroute "/todo/:fname" [fname]
  (session/put! :current-page (todo-page todo-lists fname)))

;; -------------------------
;; Initialize app

; initiate the long-poller
(long-poller todo-lists (swap! instance inc))

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!)
  (accountant/dispatch-current!)
  (mount-root))
