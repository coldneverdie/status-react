(ns status-im.ui.screens.chat.uiperf
  (:require [reagent.core :as reagent]))

(def render-perf-mode (reagent/atom false))

(def logs (reagent/atom []))

(defn add-log [& opts]
  (let [s (str "perf: " @render-perf-mode "|" (reduce #(str %1 " " %2) "" opts))]
    (swap! logs conj s)
    (println s)))