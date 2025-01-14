(ns fluree.db.indexer.proto
  (:refer-clojure :exclude [-add-watch -remove-watch]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol iIndex
  (-index? [indexer db] "Returns true if db requires a reindex")
  (-halt? [indexer db] "Returns true if new transactions need to be blocked based on reindex max threshold being hit")
  (-index [indexer db] [indexer db opts]  "Executes index operation, returns a promise chan with indexed db once complete.")
  (-add-watch [indexer id callback]  "Provided callback fn will be executed with new indexing events.")
  (-remove-watch [indexer id]  "Removes watch fn.")
  (-push-event [indexer event-data] "Pushes an index event (map) to all watchers")
  (-register-commit-fn [indexer branch f] "Adds a function that will update/push a new commit with an updated index when provided the reindexed db as single arg")
  (-close [indexer]  "Shuts down indexer, removes all watches after notification.")
  (-status [indexer]  "Returns current status of reindexing.")
  (-empty-novelty [indexer db] [indexer db t]"Returns db with emptied novelty, when 't' provided only empties novelty at or before 't'")
  (-reindex [indexer db]  "Executes a full reindex on db."))
