(ns status-im.ui.screens.chat.uiperf
  (:require [reagent.core :as reagent]
            [status-im.ui.components.topbar :as topbar]
            [status-im.ui.components.list.views :as list]
            [quo.react-native :as rn]
            [quo.core :as quo]))

(def render-perf-mode (reagent/atom false))

(def logs (reagent/atom []))

(defn add-log [& opts]
  (let [s (str "perf: " @render-perf-mode "|" (reduce #(str %1 " " %2) "" opts))]
    (swap! logs conj s)
    (println s)))


(defn view []
  [:<>
   [topbar/topbar]
   [quo/button {:on-press #(reset! logs [])} "clear"]
   [list/flat-list
    {:data      @logs
     :render-fn (fn [s]
                  [rn/text s])}]])