(ns fluree.db.storage.core
  (:refer-clojure :exclude [read exists? list])
  (:require [fluree.db.serde.protocol :as serdeproto]
            [fluree.db.flake :as flake]
            [clojure.string :as str]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.index :as index]
            [fluree.db.dbproto :as dbproto]
            [clojure.core.async :refer [go <!] :as async]
            [fluree.db.util.async #?(:clj :refer :cljs :refer-macros) [<? go-try]]
            [fluree.db.util.core :as util #?(:clj :refer :cljs :refer-macros) [try* catch*]]
            [fluree.db.query.schema :as schema]
            [fluree.db.json-ld.vocab :as vocab]
            #?(:clj [clojure.java.io :as io])))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol Store
  (exists? [s k] "Returns true when `k` exists in `s`")
  (list [s d] "Returns a collection containing the keys stored under the subdirectory/prefix `d` of `s`")
  (read [s k] "Reads raw bytes from `s` associated with `k`")
  (write [s k data] "Writes `data` as raw bytes to `s` and associates it with `k`")
  (rename [s old-key new-key] "Remove `old-key` and associate its data to `new-key`")
  (delete [s k] "Delete data associated with key `k`"))

#?(:clj
   (defn block-storage-path
     "For a ledger server, will return the relative storage path it is using for blocks for a given ledger."
     [network ledger-id]
     (io/file network ledger-id "block")))

(defn serde
  "Returns serializer from connection."
  [conn]
  (:serializer conn))

(defn ledger-root-key
  [network ledger-id block]
  (str network "_" ledger-id "_root_" (util/zero-pad block 15)))

(defn ledger-garbage-prefix
  [network ldgr-id]
  (str/join "_" [network ldgr-id "garbage"]))

(defn ledger-garbage-key
  [network ldgr-id block]
  (let [pre (ledger-garbage-prefix network ldgr-id)]
    (str/join "_" [pre block])))

(defn ledger-node-key
  [network ledger-id idx-type base-id node-type]
  (str network "_" ledger-id "_" (name idx-type) "_" base-id "-" node-type))

(defn ledger-block-key
  [network ledger-id block]
  (str network "_" ledger-id "_block_" (util/zero-pad block 15)))

(defn ledger-block-file-path
  [network ledger-id block]
  (str network "/" ledger-id "/block/" (util/zero-pad block 15)))

(defn read-block
  "Returns a core async channel with the requested block."
  [conn network ledger-id block]
  (go-try
    (let [key  (ledger-block-key network ledger-id block)
          data (<? (read conn key))]
      (when data
        (serdeproto/-deserialize-block (serde conn) data)))))

(defn read-block-version
  "Returns a core async channel with the requested block."
  [conn network ledger-id block version]
  (go-try
    (let [key  (str (ledger-block-key network ledger-id block) "--v" version)
          data (<? (read conn key))]
      (when data
        (serdeproto/-deserialize-block (serde conn) data)))))

(defn write-block-version
  "Block data should look like:

  {:block  block (long)
   :flakes flakes
   :hash   hash
   :sigs   sigs
   :txns   {tid (tx-id, string) {:cmd command (JSON string)
                                 :sig signature (string}]}
  "
  [conn network ledger-id block-data version]
  (go-try
    (let [persisted-data (select-keys block-data [:block :t :flakes])
          key            (str (ledger-block-key network ledger-id
                                                (:block persisted-data))
                              "--v" version)
          ser            (serdeproto/-serialize-block (serde conn) persisted-data)]
      (<? (write conn key ser)))))

(defn write-block
  "Block data should look like:

  {:block  block (long)
   :flakes flakes
   :hash   hash
   :sigs   sigs
   :txns   {tid (tx-id, string) {:cmd command (JSON string)
                                 :sig signature (string}]}
  "
  [conn network ledger-id block-data]
  (go-try
    (let [persisted-data (select-keys block-data [:block :t :flakes])
          key            (ledger-block-key network ledger-id (:block persisted-data))
          ser            (serdeproto/-serialize-block (serde conn) persisted-data)]
      (<? (write conn key ser)))))

(defn child-data
  "Given a child, unresolved node, extracts just the data that will go into
  storage."
  [child]
  (select-keys child [:id :leaf :first :rhs :size]))

(defn random-leaf-id
  [network ledger-id idx]
  (ledger-node-key network ledger-id idx (random-uuid) "l"))

(defn write-leaf
  "Writes `leaf` to storage under the provided `leaf-id`, computing a new id if
  one isn't provided. Returns the leaf map with the id used attached uner the
  `:id` key"
  ([conn network ledger-id idx-type leaf]
   (let [leaf-id (random-leaf-id network ledger-id idx-type)]
     (write-leaf conn network ledger-id idx-type leaf-id leaf)))

  ([conn network ledger-id idx-type leaf-id {:keys [flakes] :as leaf}]
   (go-try
     (let [data {:flakes flakes}
           ser  (serdeproto/-serialize-leaf (serde conn) data)
           res  (<? (write conn leaf-id ser))]
       (assoc leaf :id (or (:address res) leaf-id))))))

(defn write-branch-data
  "Serializes final data for branch and writes it to provided key"
  [conn key data]
  (go-try
    (let [ser (serdeproto/-serialize-branch (serde conn) data)]
      (<? (write conn key ser)))))

(defn random-branch-id
  [network ledger-id idx]
  (ledger-node-key network ledger-id idx (random-uuid) "b"))

(defn write-branch
  "Writes `branch` to storage under the provided `branch-id`, computing a new id
  if one isn't provided. Returns the branch map with the id used attached uner
  the `:id` key"
  ([conn network ledger-id idx-type branch]
   (let [branch-id (random-branch-id network ledger-id idx-type)]
     (write-branch conn network ledger-id idx-type branch-id branch)))

  ([conn network ledger-id idx-type branch-id {:keys [children] :as branch}]
   (go-try
     (let [child-vals  (->> children
                            (map val)
                            (mapv child-data))
           first-flake (->> child-vals first :first)
           rhs         (->> child-vals rseq first :rhs)
           data        {:children child-vals}
           res         (<? (write-branch-data conn branch-id data))]
       (assoc branch :id (or (:address res) branch-id))))))

(defn write-garbage
  "Writes garbage record out for latest index."
  [db garbage]
  (go-try
    (let [{:keys [conn network ledger-id block]} db
          garbage-key (ledger-garbage-key network ledger-id block)
          data        {:ledger-id ledger-id
                       :block     block
                       :garbage   garbage}
          ser         (serdeproto/-serialize-garbage (serde conn) data)]
      (<? (write conn garbage-key ser)))))

(defn write-db-root
  ([db]
   (write-db-root db nil))
  ([db custom-ecount]
   (go-try
     (let [{:keys [conn network commit block t ecount stats spot psot post opst
                   tspo fork fork-block]}
           db
           ledger-id (:id commit)
           db-root-key (ledger-root-key network ledger-id block)
           data        {:ledger-id ledger-id
                        :block     block
                        :t         t
                        :ecount    (or custom-ecount ecount)
                        :stats     (select-keys stats [:flakes :size])
                        :spot      (child-data spot)
                        :psot      (child-data psot)
                        :post      (child-data post)
                        :opst      (child-data opst)
                        :tspo      (child-data tspo)
                        :timestamp (util/current-time-millis)
                        :prevIndex (or (:indexed stats) 0)
                        :fork      fork
                        :forkBlock fork-block}
           ser         (serdeproto/-serialize-db-root (serde conn) data)]
       (<? (write conn db-root-key ser))))))

(defn read-branch
  [{:keys [serializer] :as conn} key]
  (go-try
    (when-let [data (<? (read conn key))]
      (serdeproto/-deserialize-branch serializer data))))

(defn read-leaf
  [{:keys [serializer] :as conn} key]
  (go-try
    (when-let [data (<? (read conn key))]
      (serdeproto/-deserialize-leaf serializer data))))

(defn reify-index-root
  "Turns each index root node into an unresolved node."
  [_conn {:keys [network ledger-id comparators block t]} index index-data]
  (let [cmp (or (get comparators index)
                (throw (ex-info (str "Internal error reifying db index root: "
                                     (pr-str index))
                                {:status 500
                                 :error  :db/unexpected-error})))]
    (cond-> index-data
            (:rhs index-data) (update :rhs flake/parts->Flake)
            (:first index-data) (update :first flake/parts->Flake)
            true (assoc :comparator cmp
                        :network network
                        :ledger-id ledger-id
                        :block block
                        :t t
                        :leftmost? true))))


(defn reify-db-root
  "Constructs db from blank-db, and ensure index roots have proper config as unresolved nodes."
  [conn blank-db root-data]
  (let [{:keys [block t ecount stats]} root-data
        db* (assoc blank-db :block block
                            :t t
                            :ecount ecount
                            :stats (assoc stats :indexed (- t)))]
    (reduce
      (fn [db idx]
        (let [idx-root (reify-index-root conn db idx (get root-data idx))]
          (assoc db idx idx-root)))
      db* index/types)))


(defn read-garbage
  "Returns a all data for a db index root of a given block."
  [conn network ledger-id block]
  (go-try
    (let [key  (ledger-garbage-key network ledger-id block)
          data (read conn key)]
      (when data
        (serdeproto/-deserialize-garbage (serde conn) (<? data))))))


(defn read-db-root
  "Returns all data for a db index root of a given block."
  ([conn idx-address]
   (go-try
     (let [data (<? (read conn idx-address))]
       (when data
         (serdeproto/-deserialize-db-root (serde conn) data)))))
  ([conn network ledger-id block]
   (go-try
     (let [key  (ledger-root-key network ledger-id block)
           data (<? (read conn key))]
       (when data
         (serdeproto/-deserialize-db-root (serde conn) data))))))


(defn reify-db
  "Reifies db at specified index point. If unable to read db-root at index,
  throws."
  ([conn blank-db idx-address]
   (go-try
     (let [db-root (<? (read-db-root conn idx-address))]
       (if-not db-root
         (throw (ex-info (str "Database " (:address blank-db)
                              " could not be loaded at index point: "
                              idx-address ".")
                         {:status 400
                          :error  :db/unavailable}))
         (let [db     (reify-db-root conn blank-db db-root)
               schema (<? (vocab/vocab-map db))
               db*    (assoc db :schema schema)]
           ;(assoc db* :settings settings-map)
           db*)))))
  ([conn network ledger-id blank-db index]
   (go-try
     (let [db-root (read-db-root conn network ledger-id index)]
       (if-not db-root
         (throw (ex-info (str "Database " network "/" ledger-id
                              " could not be loaded at index point: "
                              index ".")
                         {:status 400
                          :error  :db/unavailable}))
         (let [db           (reify-db-root conn blank-db (<? db-root))
               schema-map   (<? (schema/schema-map db))
               db*          (assoc db :schema schema-map)
               settings-map (<? (schema/setting-map db*))]
           (assoc db* :settings settings-map)))))))

(defn fetch-child-attributes
  [conn {:keys [id comparator leftmost?] :as branch}]
  (go-try
    (if-let [{:keys [children]} (<? (read-branch conn id))]
      (let [branch-metadata (select-keys branch [:comparator :network :ledger-id
                                                 :block :t :tt-id :tempid])
            child-attrs     (map-indexed (fn [i child]
                                           (-> branch-metadata
                                               (assoc :leftmost? (and leftmost?
                                                                      (zero? i)))
                                               (merge child)))
                                         children)
            child-entries   (mapcat (juxt :first identity)
                                    child-attrs)]
        (apply flake/sorted-map-by comparator child-entries))
      (throw (ex-info (str "Unable to retrieve index branch with id "
                           id " from storage.")
                      {:status 500, :error :db/storage-error})))))

(defn fetch-leaf-flakes
  [conn {:keys [id comparator]}]
  (go-try
    (if-let [{:keys [flakes] :as leaf} (<? (read-leaf conn id))]
      (apply flake/sorted-set-by comparator flakes)
      (throw (ex-info (str "Unable to retrieve leaf node with id: "
                           id " from storage")
                      {:status 500, :error :db/storage-error})))))

(defn resolve-index-node
  ([conn node]
   (resolve-index-node node nil))
  ([conn {:keys [comparator leaf] :as node} error-fn]
   (assert comparator "Cannot resolve index node; configuration does not have a comparator.")
   (let [return-ch (async/chan)]
     (go
       (try*
         (let [[k data] (if leaf
                          [:flakes (<? (fetch-leaf-flakes conn node))]
                          [:children (<? (fetch-child-attributes conn node))])]
           (async/put! return-ch
                       (assoc node k data)))
         (catch* e
           (log/error e "Error resolving index node")
           (when error-fn
             (error-fn
               (async/put! return-ch e)
               (async/close! return-ch))))))
     return-ch)))

(defn resolve-empty-leaf
  [{:keys [comparator] :as node}]
  (let [ch         (async/chan)
        empty-set  (flake/sorted-set-by comparator)
        empty-node (assoc node :flakes empty-set)]
    (async/put! ch empty-node)
    ch))
