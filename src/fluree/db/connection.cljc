(ns fluree.db.connection
  (:require [clojure.string :as str]
            #?(:clj [environ.core :as environ])
            #?(:clj  [clojure.core.async :as async :refer [go <!]]
               :cljs [cljs.core.async :as async :refer [go <!]])
            [fluree.db.util.json :as json]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.index :as index]
            [fluree.db.dbfunctions.core :as dbfunctions]
            [#?(:cljs cljs.cache :clj clojure.core.cache) :as cache]
            [fluree.db.session :as session]
            [fluree.crypto :as crypto]
            #?(:clj [fluree.db.full-text :as full-text])
            [fluree.db.util.xhttp :as xhttp]
            [fluree.db.util.core :as util
             #?@(:cljs [:refer-macros [try* catch*] :refer [exception?]])
             #?@(:clj [:refer [try* catch* exception?]])]
            [fluree.db.util.async :refer [<? go-try channel?]]
            [fluree.db.serde.json :refer [json-serde]]
            [fluree.db.query.http-signatures :as http-signatures]
            #?(:clj [fluree.db.serde.avro :refer [avro-serde]])
            [fluree.db.conn-events :as conn-events]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.storage.core :as storage]
            [fluree.db.conn.proto :as conn-proto]
            [fluree.db.did :as did]
            [fluree.json-ld :as json-ld]))

#?(:clj (set! *warn-on-reflection* true))

;; socket connections are keyed by connection-id and contain :socket - ws, :id - socket-id :health - status of health checks.
(def server-connections-atom (atom {}))

(def server-regex #"^(?:((?:https?):)//)([^:/\s#]+)(?::(\d*))?")


(defn- acquire-healthy-server
  "Tries all servers in parallel, the first healthy response will be used for the connection
  (additional server healthy writes will be no-ops after first)."
  [conn-id servers promise-chan]
  ;; kick off server comms in parallel
  (doseq [server servers]
    (let [healthcheck-uri (str server "/fdb/health")
          resp-chan       (xhttp/post-json healthcheck-uri {} {:request-timeout 5000})]
      (async/go
        (let [resp (async/<! resp-chan)]
          (if (util/exception? resp)
            (log/warn "Server contact error: " (ex-message resp) (ex-data resp))
            (async/put! promise-chan server))))))

  ;; trigger a timeout and clear pending channel if no healthy responses
  (async/go
    (let [healthy-server (async/alt! promise-chan ::server-found
                                     (async/timeout 60000) ::timeout)]
      (when (= ::timeout healthy-server)
        ;; remove lock, so next attempt tries a new server
        (swap! server-connections-atom update conn-id dissoc :server)
        ;; respond with error
        (async/put! promise-chan (ex-info (str "Unable to find healthy server before timeout.")
                                          {:status 500 :error :db/connection-error}))))))


(defn get-healthy-server
  "Returns a core async channel that will contain first healthy as it appears.

  Use with a timeout to consume, as no healthy servers may be avail."
  [conn-id servers]
  (let [lock-id      (random-uuid)
        new-state    (swap! server-connections-atom update-in [conn-id :server]
                            (fn [x]
                              (if x
                                x
                                {:lock-id lock-id
                                 :chan    (async/promise-chan)})))
        have-lock?   (= lock-id (get-in new-state [conn-id :server :lock-id]))
        promise-chan (get-in new-state [conn-id :server :chan])]
    (when have-lock?
      (acquire-healthy-server conn-id servers promise-chan))
    promise-chan))


(defn establish-socket
  [conn-id sub-chan pub-chan servers]
  (go-try
    (let [lock-id    (random-uuid)
          state      (swap! server-connections-atom update-in [conn-id :ws]
                            (fn [x]
                              (if x
                                x
                                {:lock-id lock-id
                                 :socket  (async/promise-chan)})))
          have-lock? (= lock-id (get-in state [conn-id :ws :lock-id]))
          resp-chan  (get-in state [conn-id :ws :socket])]
      (when have-lock?
        (let [healthy-server (async/<! (get-healthy-server conn-id servers))
              ws-url         (-> (str/replace healthy-server "http" "ws")
                                 (str "/fdb/ws"))
              timeout        60000
              close-fn       (fn []
                               (swap! server-connections-atom dissoc conn-id)
                               (session/close-all-sessions conn-id))]
          (if (util/exception? healthy-server)
            (do
              ;; remove web socket promise channel, it could be tried again
              (swap! server-connections-atom update conn-id dissoc :ws)
              ;; return error, so anything waiting on socket can do what it needs
              (async/put! resp-chan healthy-server))
            (xhttp/try-socket ws-url sub-chan pub-chan resp-chan timeout close-fn))))
      resp-chan)))

(defn jld-commit-write
  [conn commit-data]
  (log/debug "commit-write data: " commit-data)
  (let [storage-write (:storage-write conn)
        dbid          (-> commit-data meta :dbid)           ;; dbid exists if this is a db (not commit) write
        json          (json-ld/normalize-data commit-data)
        storage-key   (if dbid
                        (second (re-find #"^fluree:db:sha256:(.+)$" dbid))
                        (crypto/sha2-256-normalize json))]
    (if storage-key
      (storage-write (str storage-key ".jsonld") json)
      (async/go
        (ex-info (str "Unable to retrieve storage key from dbid: " dbid ".")
                 {:status 500 :error :db/unexpected-error})))))


(defn- extract-commit-key
  "If a commit formatted address, returns filestore key."
  [commit-key]
  (let [[_ k] (re-find #"^fluree:raft://(.+)" commit-key)]
    (when k
      (str (subs k 0 3) "_" k ".jsonld"))))

(defn- extract-db-key
  "If a db formatted address, returns filestore key."
  [db-key]
  (let [[_ k] (re-find #"^fluree:db:(.+)" db-key)]
    (when k
      (str (subs k 0 3) "_" k ".jsonld"))))


(defn jld-commit-read
  [conn commit-key]
  (log/debug "Commit-read request for key: " commit-key)
  (go-try
    (let [k (or (extract-commit-key commit-key)
                (extract-db-key commit-key))]
      (when-not k
        (throw (ex-info (str "Invalid commit address: " commit-key)
                        {:status 400 :error :db/invalid-commit})))
      (-> (<? (storage/read conn k))
          (json/parse false)))))


(defn jld-push!
  [conn address commit-data]
  (log/debug "Pushing ledger:" address commit-data))

;; all ledger messages are fire and forget

;; we do need to establish an upstream connection from a ledger to us, so we can propogate
;; blocks, flushes, etc.

(defrecord Connection [id servers state req-chan sub-chan pub-chan group
                       storage-read storage-list storage-write storage-exists
                       storage-rename storage-delete object-cache async-cache
                       parallelism serializer default-network transactor?
                       publish transact-handler tx-private-key tx-key-id meta
                       add-listener remove-listener close
                       ns-lookup
                       did context]

  storage/Store
  (read [_ k]
    (storage-read k))
  (write [_ k data]
    (storage-write k data))
  (exists? [_ k]
    (storage-exists k))
  (rename [_ old-key new-key]
    (storage-rename old-key new-key))
  (list [_ d]
    (storage-list d))
  (delete [_ k]
    (storage-delete k))

  index/Resolver
  (resolve
    [conn {:keys [id leaf tempid] :as node}]
    (if (= :empty id)
      (storage/resolve-empty-leaf node)
      (async-cache
        [::resolve id tempid]
        (fn [_]
          (storage/resolve-index-node conn node
                                      (fn []
                                        (async-cache [::resolve id tempid] nil)))))))

  conn-proto/iConnection
  (-did [conn] did)
  (-context [conn] context)
  (-method [conn] :file)

  ;; following used specifically for JSON-LD dbs
  conn-proto/iStorage
  (-c-read [conn commit-key] (jld-commit-read conn commit-key))
  (-c-write [conn commit-data] (jld-commit-write conn commit-data))

  conn-proto/iNameService
  (-push [conn address commit-data] (jld-push! conn address commit-data))
  (-lookup [conn address] (ns-lookup conn address))
  (-address [_ ledger-alias opts] (async/go (str "fluree:raft://" ledger-alias)))


  #?@(:clj
      [full-text/IndexConnection
       (open-storage [{:keys [storage-type] :as conn} network ledger-id lang]
                     (when-let [path (-> conn :meta :file-storage-path)]
                       (full-text/disk-index path network ledger-id lang)))]))

(defn- normalize-servers
  "Split servers in a string into a vector.

  Randomizies order, ensures uniqueness."
  [servers transactor?]
  (let [servers* (if (string? servers)
                   (str/split servers #",")
                   servers)]
    (when (and (empty? servers) (not transactor?))
      (throw (ex-info "At least one server must be supplied for connection."
                      {:status 400 :error :db/invalid-connection})))
    ;; following randomizes order
    (when (not-empty servers*)
      (loop [[server & r] servers*
             https? nil
             result #{}]
        (when-not (string? server)
          (throw (ex-info (str "Invalid server provided for connection, must be a string: " (pr-str server))
                          {:status 400 :error :db/invalid-connection})))
        (let [server    (str/replace server #".+@" "")      ;; remove any server name/token for now
              server*   (cond
                          ;; properly formatted
                          (re-matches #"^https?://.+" server)
                          server

                          (str/includes? server "//")
                          (throw (ex-info (str "Only http:// and https:// protocols currently supported for connection servers. Provided:" server)
                                          {:status 400 :error :db/invalid-connection}))

                          ;; default to http
                          :else (str "http://" server))
              ;; add port 8090 as a default
              server*   (if (re-matches #".+:[0-9]+" server*)
                          server*
                          (str server* ":8090"))
              is-https? (str/starts-with? server "https://")
              result*   (conj result server*)]
          (when-not (re-matches server-regex server*)
            (throw (ex-info (str "Invalid connection server, provide url and port only. Optionally specify http:// or https://. Provided: " server)
                            {:status 400 :error :db/invalid-connection})))
          (when (and https? (not= is-https? https?))
            (throw (ex-info (str "Connection servers must all be http or https, not a mix.")
                            {:status 400 :error :db/invalid-connection})))
          (if (empty? r)
            (shuffle result*)
            (recur r is-https? result*)))))))


(defn- closed?
  "Returns true if connection has been closed."
  [conn]
  (:close? @(:state conn)))


(defn- close-websocket
  "Closes websocket on connection if exists."
  [conn-id]
  (let [existing-socket (some-> (get-in server-connections-atom [conn-id :ws :socket])
                                (async/poll!))]
    (swap! server-connections-atom dissoc conn-id)
    (if existing-socket
      (xhttp/close-websocket existing-socket)
      false)))


(defn get-socket
  "Gets websocket from connection, or establishes one if not already done.

  Returns a core async promise channel. Check for exceptions."
  [conn]
  (go-try
    (or (get-in @server-connections-atom [(:id conn) :ws :socket])
        ;; attempt to connect
        (<? (establish-socket (:id conn) (:sub-chan conn) (:pub-chan conn) (:servers conn))))))


(defn get-server
  "returns promise channel, check for errors"
  [conn-id servers]
  (or (get-in @server-connections-atom [conn-id :server :chan])
      ;; attempt to connect
      (get-healthy-server conn-id servers)))


(defn default-publish-fn
  "Publishes message to the websocket associated with the connection."
  [conn message]
  (let [pub-chan  (:pub-chan conn)
        resp-chan (async/promise-chan)
        msg       (try* (json/stringify message)
                        (catch* e
                                (log/error "Unable to publish message on websocket. Error encoding JSON message: " message)
                                (async/put! resp-chan (ex-info (str "Error encoding JSON message: " message) {}))
                                nil))]
    (when msg
      (async/put! pub-chan [msg resp-chan]))
    resp-chan))

(defn msg-producer
  "Shuffles outgoing messages to the web socket in order."
  [{:keys [state req-chan publish]
    :as   conn}]
  (async/go-loop [i 0]
    (when-let [msg (async/<! req-chan)]
      (try*
       (let [_ (log/trace "Outgoing message to websocket: " msg)
             [operation data resp-chan opts] msg
             {:keys [req-id timeout] :or {req-id  (str (random-uuid))
                                          timeout 60000}} opts]
         (when resp-chan
           (swap! state assoc-in [:pending-req req-id] resp-chan)
           (async/go
             (let [[resp c] (async/alts! [resp-chan (async/timeout timeout)])]
               ;; clear request from state
               (swap! state update :pending-req #(dissoc % req-id))
               ;; return result
               (if (= c resp-chan)
                 resp
                 ;; if timeout channel comes back first, respond with timeout error
                 (ex-info (str "Request " req-id " timed out.")
                          {:status 408
                           :error  :db/timeout})))))
         (let [publisher  (or publish default-publish-fn)
               published? (async/<! (publisher conn [operation req-id data]))]
           (when-not (true? published?)
             (cond
               (util/exception? published?)
               (log/error published? "Error processing message in producer.")

               (nil? published?)
               (log/error "Error processing message in producer. Socket closed.")

               :else
               (log/error "Error processing message in producer. Socket closed. Published result" published?)))))
       (catch* e
               (let [[_ _ resp-chan] (when (sequential? msg) msg)]
                 (if (and resp-chan (channel? resp-chan))
                   (async/put! resp-chan e)
                   (log/error e (str "Error processing ledger request, no valid return channel: " (pr-str msg)))))))
      (recur (inc i)))))


(defn ping-transactor
  [req-chan]
  (async/put! req-chan [:ping true]))

(defn reconnect-conn
  "Returns a channel that will eventually have a websocket. Will exponentially backoff
  until connection attempts happen every two minutes. Uses the existing conn and will
  reuse the existing sub-chan and pub-chan so the msg-consumer/producer loops do not
  need to be restarted."
  [conn]
  (close-websocket (:id conn))
  (async/go-loop [backoff-seconds 1]
    (async/<! (async/timeout (* backoff-seconds 1000)))
    (let [socket (async/<! (get-socket conn))
          socket (if (util/exception? socket)
                   socket
                   (async/<! socket))]
      (if (or (nil? socket)
              (util/exception? socket))
        (do (log/error socket "Cannot establish connection to a healthy server, backing off:" backoff-seconds "s.")
            (recur (min (* 2 backoff-seconds) (* 60 2))))
        socket))))

(defn msg-consumer
  "Takes messages from peer/ledger and processes them."
  [{:keys [sub-chan req-chan keep-alive-fn keep-alive] :as conn}]
  (let [ ;; if we haven't received a message in at least this long, ping ledger.
        ;; after two pings, if still no response close connection (so connection closes before the 3rd ping, so 3x this time.)
        ping-transactor-after 2500]
    (async/go-loop [no-response-pings 0]
      (let [timeout (async/timeout ping-transactor-after)
            [msg c] (async/alts! [sub-chan timeout])]
        (cond
          ;; timeout, ping and wait
          (= c timeout)
          (if (= 2 no-response-pings)
            ;; assume connection dropped, close!
            (if keep-alive
              (do (async/<! (reconnect-conn conn))
                  (recur 0))
              (do
                (log/warn "Connection has gone stale. Perhaps network conditions are poor. Disconnecting socket.")
                (let [cb keep-alive-fn]
                  (cond
                    (nil? keep-alive-fn)
                    (log/trace "No keep-alive callback is registered")

                    (fn? keep-alive-fn)
                    (keep-alive-fn)

                    (string? keep-alive-fn)
                    #?(:cljs
                       ;; try javascript eval
                       (eval keep-alive-fn)
                       :clj
                       (log/warn "Unsupported clojure callback registered" {:keep-alive-fn keep-alive-fn}))

                    :else
                    (log/warn "Unsupported callback registered" {:keep-alive-fn keep-alive-fn})))
                (close-websocket (:id conn))
                (session/close-all-sessions (:id conn))))
            (do
              (ping-transactor req-chan)
              (recur (inc no-response-pings))))

          (nil? msg)
          (log/info "Connection closed.")

          (util/exception? msg)
          (do
            (log/error msg)
            (recur 0))

          :else
          (do
            (log/trace "Received message:" (pr-str (json/parse msg)))
            (conn-events/process-events conn (json/parse msg))
            (recur 0)))))))


(defn- default-storage-read
  "Default storage read function - uses ledger storage and issues http(s) requests."
  ([conn-id servers] (default-storage-read conn-id servers nil))
  ([conn-id servers opts]
   (let [{:keys [private jwt]} opts]
     (fn [k]
       (go-try
         (let [jwt' #?(:clj jwt
                       :cljs (or jwt
                                 (get-in @server-connections-atom [conn-id :token])))
               path         (str/replace k "_" "/")
               address      (async/<! (get-server conn-id servers))
               url          (str address "/fdb/storage/" path)
               headers      (cond-> {"Accept" "application/json"}
                                    jwt' (assoc "Authorization" (str "Bearer " jwt')))
               headers*     (if private
                              (-> (http-signatures/sign-request "get" url {:headers headers} private)
                                  :headers)
                              headers)
               res          (<? (xhttp/get url {:request-timeout 5000
                                                :headers         headers*
                                                :output-format   :json}))]

           res))))))


(defn- lookup-cache
  [cache-atom k value-fn]
  (if (nil? value-fn)
    (swap! cache-atom cache/evict k)
    (when-let [v (get @cache-atom k)]
      (do (swap! cache-atom cache/hit k)
          v))))

(defn- default-object-cache-fn
  "Default synchronous object cache to use for ledger."
  [cache-atom]
  (fn [k value-fn]
    (if-let [v (lookup-cache cache-atom k value-fn)]
      v
      (let [v (value-fn k)]
        (swap! cache-atom cache/miss k v)
        v))))

(defn- default-async-cache-fn
  "Default asynchronous object cache to use for ledger."
  [cache-atom]
  (fn [k value-fn]
    (let [out (async/chan)]
      (if-let [v (lookup-cache cache-atom k value-fn)]
        (async/put! out v)
        (go
          (let [v (<! (value-fn k))]
            (when-not (exception? v)
              (swap! cache-atom cache/miss k v))
            (async/put! out v))))
      out)))


(defn- default-object-cache-factory
  "Generates a default object cache."
  [cache-size]
  (cache/lru-cache-factory {} :threshold cache-size))


(defn- from-environment
  "Gets a specific key from the environment, returns nil if doesn't exist."
  [key]
  #?(:clj  (get environ/env key)
     :cljs nil))


(defn listeners
  "Returns list of listeners"
  [conn]
  (-> @(:state conn)
      (:listeners)))


(defn- add-listener*
  "Internal call to add-listener that uses the state atom directly."
  [conn-state network ledger-id key fn]
  (when-not (fn? fn)
    (throw (ex-info "add-listener fn paramer not a function."
                    {:status 400 :error :db/invalid-listener})))
  (when (nil? key)
    (throw (ex-info "add-listener key must not be nil."
                    {:status 400 :error :db/invalid-listener})))
  (swap! conn-state update-in
         [:listeners [network ledger-id] key]
         #(if %
            (throw (ex-info (str "add-listener key already in use: " (pr-str key))
                            {:status 400 :error :db/invalid-listener}))
            fn))
  true)


(defn- remove-listener*
  "Internal call to remove-listener that uses the state atom directly."
  [conn-state network ledger-id key]
  (if (get-in @conn-state [:listeners [network ledger-id] key])
    (do
      (swap! conn-state update-in [:listeners [network ledger-id]] dissoc key)
      true)
    false))


(defn add-listener
  "Registers a new listener function, fn,  on connection.

  Each listener must have an associated key, which is used to remove the listener
  when needed but is otherwise opaque to the function. Each key must be unique for the
  given network + ledger-id."
  [conn network ledger-id key fn]
  ;; load db to make sure ledger events subscription initiated
  (let [ledger (str network "/" ledger-id)
        db     (session/db conn ledger nil)]
    ;; check that db exists, else throw
    #?(:clj (when (util/exception? (async/<!! db))
              (throw (async/<!! db))))
    (add-listener* (:state conn) network ledger-id key fn)))


(defn remove-listener
  "Removes listener on given network + ledger-id for the provided key.

  The key is the same provided for add-listener when registering.

  Will return true if a function exists for that key and it was removed."
  [conn network ledger-id key]
  (remove-listener* (:state conn) network ledger-id key))


(defn add-token
  "Adds token to connection information so it is available to submit storage read requests.

  Returns true if successful, false otherwise."
  [conn token]
  (let [conn-id (:id conn)]
    (try*
      (swap! server-connections-atom update-in [conn-id :token] #(or % token))
      true
      (catch* e
              false))))

(defn- generate-connection
  "Generates connection object."
  [servers opts]
  (let [state-atom         (atom {:close?       false
                                  ;; map of transactors and the latest 'health' request results
                                  :health       {}
                                  ;; which of the transactors we are connected to
                                  :connected-to nil
                                  ;; web socket connection to ledger
                                  :socket       nil
                                  ;; web socket id
                                  :socket-id    nil
                                  ;; map of pending request ids to async response channels
                                  :pending-req  {}
                                  ;; map of listener functions registered. key is two-tuple of [network ledger-id],
                                  ;; value is vector of single-argument callback functions that will receive [header data]
                                  :listeners    {}})
        {:keys [storage-read storage-exists storage-write storage-rename storage-delete storage-list
                parallelism req-chan sub-chan pub-chan default-network group
                object-cache async-cache close-fn serializer
                tx-private-key private-key-file memory
                transactor? transact-handler publish meta memory?
                ns-lookup
                private keep-alive-fn keep-alive context]
         :or   {memory           1000000                    ;; default 1MB memory
                parallelism      4
                req-chan         (async/chan)
                sub-chan         (async/chan)
                pub-chan         (async/chan)
                memory?          false
                storage-write    (fn [k v] (throw (ex-info (str "Storage write was not implemented on connection, but was called to store key: " k) {})))
                serializer       (json-serde)
                transactor?      false
                private-key-file "default-private-key.txt"}} opts
        memory-object-size (quot memory 100000)             ;; avg 100kb per cache object
        _                  (when (< memory-object-size 10)
                             (throw (ex-info (str "Must allocate at least 1MB of memory for Fluree. You've allocated: " memory " bytes.") {:status 400 :error :db/invalid-configuration})))
        default-cache-atom (atom (default-object-cache-factory memory-object-size))
        object-cache-fn    (or object-cache
                               (default-object-cache-fn default-cache-atom))
        async-cache-fn     (or async-cache
                               (default-async-cache-fn default-cache-atom))
        conn-id            (str (random-uuid))
        close              (fn []
                             (async/close! req-chan)
                             (async/close! sub-chan)
                             (async/close! pub-chan)
                             (close-websocket conn-id)
                             (swap! state-atom assoc :close? true)
                             (session/close-all-sessions conn-id)
                             (reset! default-cache-atom (default-object-cache-factory memory-object-size))
                             ;; user-supplied close function
                             (when (fn? close-fn) (close-fn))
                             (log/info "connection closed"))
        servers*           (normalize-servers servers transactor?)
        storage-read*      (or storage-read (default-storage-read conn-id servers* opts))
        storage-exists*    (or storage-exists storage-read (default-storage-read conn-id servers* opts))
        _                  (when-not (fn? storage-read*)
                             (throw (ex-info (str "Connection's storage-read must be a function. Provided: " (pr-str storage-read))
                                             {:status 500 :error :db/unexpected-error})))
        _                  (when-not (fn? storage-exists*)
                             (throw (ex-info (str "Connection's storage-exists must be a function. Provided: " (pr-str storage-exists))
                                             {:status 500 :error :db/unexpected-error})))
        _                  (when (and storage-write (not (fn? storage-write)))
                             (throw (ex-info (str "Connection's storage-write, if provided, must be a function. Provided: " (pr-str storage-write))
                                             {:status 500 :error :db/unexpected-error})))
        settings           {:meta             meta
                            ;; supplied static metadata, used mostly by ledger to add additional info
                            :id               conn-id
                            :servers          servers*
                            :state            state-atom
                            :req-chan         req-chan
                            :sub-chan         sub-chan
                            :pub-chan         pub-chan
                            :close            close
                            :group            group
                            :storage-list     storage-list
                            :storage-read     storage-read*
                            :storage-exists   storage-exists*
                            :storage-write    storage-write
                            :storage-rename   storage-rename
                            :storage-delete   storage-delete
                            :object-cache     object-cache-fn
                            :async-cache      async-cache-fn
                            :parallelism      parallelism
                            :serializer       serializer
                            :default-network  default-network
                            :transact-handler transact-handler ;; only used for transactors
                            :transactor?      transactor?
                            :memory           memory?
                            :publish          publish       ;; publish function for transactors
                            :tx-private-key   tx-private-key
                            :tx-key-id        (when tx-private-key
                                                #?(:clj  (crypto/account-id-from-private tx-private-key)
                                                   :cljs nil))
                            :keep-alive       keep-alive
                            :keep-alive-fn    (when (or (fn? keep-alive-fn) (string? keep-alive-fn))
                                                keep-alive-fn)
                            :add-listener     (partial add-listener* state-atom)
                            :remove-listener  (partial remove-listener* state-atom)
                            :did              (when tx-private-key
                                                (did/private->did-map tx-private-key))
                            :ns-lookup        ns-lookup
                            :context          context}]
    (map->Connection settings)))

(defn close!
  "Closes connection, returns true if close successful, false if already closed."
  [conn]
  (if (closed? conn)
    false
    (do
      ;; execute close function
      ((:close conn))
      true)))


(defn connect
  "Creates a connection to a ledger group server.
  Provide servers in either a sequence or as a string that is comma-separated."
  [servers & [opts]]
  (let [conn        (generate-connection servers opts)
        transactor? (:transactor? opts)]
    (when-not transactor?
      (async/go
        (let [socket (async/<! (get-socket conn))]
          (if (or (nil? socket)
                  (util/exception? socket))
            (do
              (log/error socket "Cannot establish connection to a healthy server, disconnecting.")
              (async/close! conn))
            ;; kick off consumer
            (msg-consumer conn)))))

    (msg-producer conn)

    conn))
