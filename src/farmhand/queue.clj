(ns farmhand.queue
  (:require [clojure.java.io :as io]
            [farmhand.jobs :as jobs]
            [farmhand.redis :as r :refer [with-jedis]]
            [farmhand.registry :as registry]
            [farmhand.utils :refer [now-millis]])
  (:import (redis.clients.jedis Transaction)))

(set! *warn-on-reflection* true)

(defn all-queues-key ^String [] (r/redis-key "queues"))
(defn in-flight-key ^String [] (r/redis-key "inflight"))
(defn completed-key ^String [] (r/redis-key "completed"))
(defn queue-key ^String [queue-name] (r/redis-key "queue:" queue-name))

(defn push
  [^Transaction transaction {:keys [queue job-id] :as job}]
  (let [queue-key (queue-key queue)]
    (.sadd transaction (all-queues-key) (r/str-arr queue))
    (.lpush transaction queue-key (r/str-arr job-id))
    (jobs/update-props transaction job-id {:status "queued"})))

(defn queue-order
  "Accepts a sequence of queue maps and returns a vector of queue names.

  Each queue map has keys:

  :name
  Name of the queue

  :priority
  (optional) This determines precedence of a queue. If queue A has a higher
  priority than queue B, then ALL jobs in queue A must be consumed before any
  in queue B will run.

  :weight
  (optional) A weight to give the queue. This is different than :priority. When
  queues A and B have the same priority, but queue A has weight 2 and queue B
  has weight 1, then queue A will be used twice as often as queue B."
  [queue-defs]
  {:post [(vector? %)]}
  (->> queue-defs
       ;; Take weight into consideration. If a queue has a a weight of N, we
       ;; repeat that queue N times in the resulting list.
       (mapcat #(repeat (get % :weight 1) %))
       ;; Shuffle to avoid one queue being treated with a higher priority when
       ;; there are other queues of the same priority.
       (shuffle)
       ;; Sort to take priority into account. Queues with higher priority will
       ;; jump to the top.
       (sort-by :priority #(compare %2 %1))
       (mapv :name)))

(def ^:private ^String dequeue-lua (slurp (io/resource "farmhand/dequeue.lua")))

(defn dequeue
  [pool queue-names]
  {:pre [(vector? queue-names)]}
  (let [keys (mapv queue-key queue-names)
        now-str (str (now-millis))
        params (r/seq->str-arr (conj keys (in-flight-key) now-str))
        num-keys ^Integer (inc (count keys))]
    (with-jedis pool jedis
      (.eval jedis dequeue-lua num-keys params))))

(defn complete
  [job-id pool & {:keys [result]}]
  (with-jedis pool jedis
    (let [transaction (.multi jedis)]
      (jobs/update-props transaction job-id {:status "complete"
                                             :result result
                                             :completed-at (now-millis)})
      (jobs/set-ttl transaction job-id)
      (registry/delete transaction (in-flight-key) job-id)
      (registry/add transaction (completed-key) job-id)
      (.exec transaction))))
