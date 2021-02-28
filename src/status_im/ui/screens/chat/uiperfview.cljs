(ns status-im.ui.screens.chat.uiperfview
  (:require [status-im.ui.components.topbar :as topbar]
            [quo.core :as quo]
            [status-im.ui.components.list.views :as list]
            [status-im.ui.screens.chat.uiperf :as uiperf]
            [quo.react-native :as rn]))

(defn view []
  [:<>
   [topbar/topbar]
   [quo/button {:on-press #(reset! uiperf/logs [])} "clear"]
   [list/flat-list
    {:data      @uiperf/logs
     :render-fn (fn [s]
                  [rn/text s])}]])