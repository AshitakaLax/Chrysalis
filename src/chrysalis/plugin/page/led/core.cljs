;; Chrysalis -- Kaleidoscope Command Center
;; Copyright (C) 2017  Gergely Nagy <algernon@madhouse-project.org>
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns chrysalis.plugin.page.led.core
  (:refer-clojure :exclude [slurp])
  (:require [chrysalis.core :refer [state pages]]
            [chrysalis.ui :refer [page color->hex]]
            [chrysalis.hardware :as hardware]
            [chrysalis.device :as device]
            [chrysalis.command :as command]

            [reagent.core :as reagent :refer [atom]]
            [hickory.core :as h]
            [clojure.walk :as walk]
            [clojure.string :as s]))

(defn- get-theme! []
  (let [port (hardware/open (get-in (device/current) [:device :comName]))
        theme (command/run port :led.theme)]
    (.setTimeout js/window #(.close port) 100)
    (swap! state assoc-in [:led :theme] theme)))

(defn- set-theme! [theme]
  (let [port (hardware/open (get-in (device/current) [:device :comName]))]
    (command/run port :led.theme theme)
    (.setTimeout js/window #(.close port) 2000)))

(defn hex->color [hex]
  (let [r (js/parseInt (.substring hex 1 3) 16)
        g (js/parseInt (.substring hex 3 5) 16)
        b (js/parseInt (.substring hex 5 7) 16)]
    [r g b]))

(defn- key-index [r c cols]
  (if-let [led-map (get-in (device/current) [:device :led :map])]
    (nth (nth led-map r) c)
    (+ (* r cols) c)))

(defn current-node? [r c]
  (if-let [target (get-in @state [:led :current-target])]
    (let [cr (js/parseInt (.getAttribute target "data-row"))
          cc (js/parseInt (.getAttribute target "data-column"))]
      (and (= r cr) (= c cc)))
    false))

(defn- node-augment [node theme]
  (let [[r c] (map js/parseInt (rest (re-find #"R(\d+)C(\d+)_F" (:id node))))]
    (if (and r c)
      (let [[rows cols] (get-in (device/current) [:device :meta :matrix])
            index (key-index r c cols)
            color (nth theme index [0 0 0])]
        (assoc node
               :data-row r
               :data-column c
               :data-index index
               :fill (color->hex color)
               :stroke-width (if (current-node? r c)
                               3
                               0)
               :stroke (if (current-node? r c)
                         "#ff0000"
                         "#000000")
               :on-click (fn [e]
                           (let [target (.-target e)]
                             (swap! state assoc-in [:led :current-target] target)))))
      node)))

(defn with-colors [svg theme]
  (walk/prewalk (fn [node]
                  (if (and (map? node) (get node :id))
                    (node-augment node theme)
                    node))
                svg))

(defn <led-theme> [file-name theme props]
  (let [fs (js/require "fs")
        result (atom nil)]
    (.readFile fs file-name "utf8"
               (fn [err data]
                 (when-not err
                   (reset! result (-> data
                                      h/parse
                                      h/as-hiccup
                                      first
                                      (nth 3)
                                      (nth 2)
                                      (assoc 1 (assoc props :view-box "0 0 2048 1280")))))))
    (fn []
      (if @theme
        (with-colors @result @theme)
        [:i.fa.fa-refresh.fa-spin.fa-5x]))))

(defmethod page [:enter :led] [_ _]
  (get-theme!))

(defn picker []
  (if-let [target (get-in @state [:led :current-target])]
    (when-let [color (.getAttribute target "fill")]
      [:form.input-group {:on-submit (fn [e]
                                       (.preventDefault e)
                                       (let [target (get-in @state [:led :current-target])
                                             index (js/parseInt (.getAttribute target "data-index"))
                                             new-color (get-in @state [:led :new-color])
                                             theme (get-in @state [:led :theme])]
                                         (swap! theme assoc index (hex->color new-color))))}
       [:input.form-control {:type :text
                             :placeholder color
                             :on-change (fn [e]
                                          (swap! state assoc-in [:led :new-color] (.-value (.-target e))))}]
       [:button.input-group-addon.btn {:style {:background-color color}} [:tt color]]])))

(defmethod page [:render :led] [_ _]
  [:div.container-fluid
   [:div.row.justify-content-center
    [:div.col-sm-12.text-center
     [:h2 "LED Theme Editor"]]]
   [:div.row
    [:div.col-sm-9.text-center
     [<led-theme> (get-in (device/current) [:device :meta :layout])
      (get-in @state [:led :theme])
      {:width 1024 :height 640}]
     [:button.btn.btn-primary {:type :button
                               :on-click (fn [e]
                                           (let [theme (get-in @state [:led :theme])
                                                 theme-str (s/join " " (flatten @theme))]
                                             (set-theme! theme-str)))}
      "Apply"]]
    [:div.col-sm-3.text-center.bg-faded
     "Color picker"
     [picker]]]])

(swap! pages assoc :led {:name "LED Theme Editor"
                         :index 10
                         :disable? (fn [] (nil? (device/current)))})
