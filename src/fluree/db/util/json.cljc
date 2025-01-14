(ns fluree.db.util.json
  (:require #?(:clj [cheshire.core :as cjson])
            #?(:clj [cheshire.parse :as cparse])
            #?(:clj [cheshire.generate :refer [add-encoder encode-seq remove-encoder]])
            #?(:clj [fluree.db.flake])
            #?(:cljs [goog.object :as gobject])
            [fluree.db.util.bytes :as butil]
            [fluree.db.util.core :as cutil]
            [fluree.db.util.log :as log]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake])
  #?(:clj
     (:import (fluree.db.flake Flake)
              (java.io ByteArrayInputStream)
              (byte_streams InputStream)
              (com.fasterxml.jackson.core JsonGenerator))))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj (add-encoder Flake encode-seq))

#?(:clj (add-encoder (Class/forName "[B") encode-seq))

#?(:clj
   (defn encode-BigDecimal-as-string
     "Turns on/off json-encoding of a java.math.Bigdecimal as a string"
     [enable]
     (if enable
       (add-encoder java.math.BigDecimal
                     (fn [n ^JsonGenerator jsonGenerator]
                       (.writeString jsonGenerator (str n))))
       (remove-encoder java.math.BigDecimal))))

;;https://purelyfunctional.tv/mini-guide/json-serialization-api-clojure/
#?(:cljs
   (defn clj->js'
     ([x] (clj->js' x {:keyword-fn cutil/keyword->str}))
     ([x options]
      (let [{:keys [keyword-fn]} options]
        (letfn [(keyfn [k] (key->js k thisfn))
                (thisfn [x] (cond
                              (nil? x) nil
                              (satisfies? IEncodeJS x) (-clj->js x)
                              (keyword? x) (keyword-fn x)
                              (symbol? x) (str x)
                              (map? x) (let [m (js-obj)]
                                         (doseq [[k v] x]
                                           (gobject/set m (keyfn k) (thisfn v)))
                                         m)
                              (coll? x) (let [arr (array)]
                                          (doseq [x (map thisfn x)]
                                            (.push arr x))
                                          arr)
                              :else x))]
          (thisfn x))))))

(defn parse
  ([x] (parse x true))
  ([x keywordize-keys?]
   #?(:clj  (-> (cond (string? x) x
                      (bytes? x) (butil/UTF8->string x)
                      (instance? ByteArrayInputStream x) (slurp x)
                      (instance? InputStream x) (slurp x)
                      :else (throw (ex-info (str "json parse error, unknown input type: " (pr-str (type x)))
                                            {:status 500 :error :db/unexpected-error})))
                ;; set binding parameter to decode BigDecimals
                ;; without truncation.  Unfortunately, this causes
                ;; all floating point and doubles to be designated
                ;; as BigDecimals.
                (as-> x'
                      (binding [cparse/*use-bigdecimals?* true]
                        (cjson/decode x' keywordize-keys?))))
      :cljs (-> (if (string? x)
                  x
                  (butil/UTF8->string x))
                (js/JSON.parse)
                (js->clj :keywordize-keys keywordize-keys?)))))


#?(:cljs
   (defn stringify-preserve-namespace
     [x]
     (js/JSON.stringify (clj->js' x))))

(defn stringify
  [x]
  #?(:clj  (cjson/encode x)
     :cljs (js/JSON.stringify (clj->js x))))


(defn stringify-UTF8
  [x]
  (butil/string->UTF8 (stringify x)))


(defn parse-json-flakes
  [db flakes]
  (log/debug "parse-json-flakes flakes:" flakes)
  ;; TODO: Should we cache these predicate id -> type mappings?
  (map #(if (= :json (->> % flake/p (dbproto/-p-prop db :type)))
          (update % :o parse)
          %)
       flakes))


(defn- valid-coordinates?
  "Given a sequence of coordinates, ensure that, for the given depth:
   1) they contain only sequences until
   2) they contain only numbers at depth 1"
  [depth coordinates]
  {:pre [(pos? depth)]}
  (if (= 1 depth)
    (and (every? number? coordinates)
         (<= 2 (count coordinates)))
    (and (not (nil? coordinates))
         (every? sequential? coordinates)
         (->> coordinates
              (map #(valid-coordinates? (dec depth) %))
              (every? true?)))))

(defn- linear-ring?
  "Checks to make sure that the given coordinates are valid linear rings, which
   is a requirement for Polygon types."
  [coordinates]
  (and (sequential? coordinates)
       (<= 4 (count coordinates))
       (= (first coordinates) (last coordinates))))

(defmulti valid-geojson? :type)

(defmethod valid-geojson? "Feature" [geometry]
  (and (or (valid-geojson? (:geometry geometry))
           (nil? (:geometry geometry)))
       (or (map? (:properties geometry))
           (nil? (:properties geometry)))))

(defmethod valid-geojson? "FeatureCollection" [geometry]
  (and (sequential? (:features geometry))
       (->> geometry
            :features
            (map valid-geojson?)
            (every? true?))))

(defmethod valid-geojson? "GeometryCollection" [geometry]
  (and (sequential? (:geometries geometry))
       (->> geometry
            :geometries
            (map valid-geojson?)
            (every? true?))))

(defmethod valid-geojson? "Point" [geometry]
  (valid-coordinates? 1 (:coordinates geometry)))

(defmethod valid-geojson? "LineString" [geometry]
  (valid-coordinates? 2 (:coordinates geometry)))

(defmethod valid-geojson? "Polygon" [geometry]
  (and (valid-coordinates? 3 (:coordinates geometry))
       (every? linear-ring? (:coordinates geometry))))

(defmethod valid-geojson? "MultiPoint" [geometry]
  (valid-coordinates? 2 (:coordinates geometry)))

(defmethod valid-geojson? "MultiLineString" [geometry]
  (valid-coordinates? 3 (:coordinates geometry)))

(defmethod valid-geojson? "MultiPolygon" [geometry]
  (and (valid-coordinates? 4 (:coordinates geometry))
       (every? #(every? linear-ring? %) (:coordinates geometry))))

(defmethod valid-geojson? :default [geometry] false)

(comment

  (-> {:type        "Point"
       :coordinates [0 0]}
      (stringify)
      (parse)
      (valid-geojson?)))

