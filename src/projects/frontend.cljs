(ns projects.frontend
  (:require [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [reagent.dom.client :as rdom]))
;; -----------------------------------------------------------------------------
;; Single state unit for the MCP demo
;; -----------------------------------------------------------------------------
;; Intentional constraint:
;; - one app-db key
;; - one event
;; - one subscription
;; - one visible UI value
;; This keeps the demo extremely clear while still proving live REPL control.
(def default-db
  {:demo/message "Initial value from :app/init"})
;; -----------------------------------------------------------------------------
;; Events
;; -----------------------------------------------------------------------------
;; Event 1: initialize app-db.
(rf/reg-event-db
  :app/init
  (fn [_ _]
    default-db))
;; Event 2: the only mutation event used in the live demo.
;; Dispatching this event from clojurescript_eval should update the browser UI
;; immediately through the single subscription below.
(rf/reg-event-db
  :demo/set-message
  (fn [db [_ new-message]]
    (assoc db :demo/message (str new-message))))
;; -----------------------------------------------------------------------------
;; Subscriptions
;; -----------------------------------------------------------------------------
;; One subscription to one key. This is the value MCP eval will read.
(rf/reg-sub :demo/message (fn [db _] (:demo/message db)))
;; -----------------------------------------------------------------------------
;; Views
;; -----------------------------------------------------------------------------
(defn app []
  (let [message @(rf/subscribe [:demo/message])]
    [:main {:style {:font-family "ui-sans-serif, system-ui, sans-serif"
                    :max-width   "760px"
                    :margin      "2rem auto"
                    :padding     "0 1rem"}}
     [:h1 "Projects Shadow App (minimal re-frame demo)"]
     [:p "One state key, one event, one subscription, one output value."]
     [:button
      {:on-click #(rf/dispatch [:demo/set-message
                                (str "Button dispatch at "
                                  (.toLocaleTimeString (js/Date.)))])}
      "Dispatch :demo/set-message"]
     [:h2 {:style {:margin-top "1rem"}} message]]))
;; -----------------------------------------------------------------------------
;; MCP convenience helpers
;; -----------------------------------------------------------------------------
;; These helpers make live demos easier from browser console and eval tooling.
(defn dispatch! [event]
  (rf/dispatch event))
(defn state-snapshot []
  @rf-db/app-db)
(defn current-message []
  @(rf/subscribe [:demo/message]))
;; -----------------------------------------------------------------------------
;; App bootstrap
;; -----------------------------------------------------------------------------
(defonce root* (atom nil))
(defn mount! []
  (let [el (.getElementById js/document "app")]
    (when-not el
      (throw (js/Error. "Missing #app element in resources/public/index.html")))
    (when-not @root*
      (reset! root* (rdom/create-root el)))
    (rdom/render @root* [app])))
(defn init []
  ;; `dispatch-sync` guarantees DB is ready before first render.
  (rf/dispatch-sync [:app/init])
  (mount!)
  ;; Window hooks intentionally exposed for live video demos.
  (set! (.-mcpDispatch js/window) dispatch!)
  (set! (.-mcpState js/window) state-snapshot)
  (set! (.-mcpMessage js/window) current-message)
  (js/console.log "projects.frontend/init loaded with re-frame + MCP helpers"))
