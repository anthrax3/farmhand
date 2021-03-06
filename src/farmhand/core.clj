(ns farmhand.core
  (:require [clojure.core.async :as async]
            [farmhand.config :as config]
            [farmhand.handler :as handler]
            [farmhand.jobs :as jobs]
            [farmhand.queue :as queue]
            [farmhand.redis :as redis :refer [with-transaction]]
            [farmhand.registry :as registry]
            [farmhand.work :as work]))

(defonce
  ^{:doc "An atom that contains the most recent context created by
         create-context.

         For many applications, this atom will do the trick when you want to
         spin up a single server in your application and have easy access to
         the context."}
  context*
  (atom nil))

(defonce
  ^{:doc "An atom that contains the most recent server that was spun up. You
         can use this to easily stop a server without needing to store the
         server instance yourself."}
  server*
  (atom nil))

(defn enqueue
  "Pushes a job onto the queue. Returns the job's ID.

  The (optional) first argument is a Farmhand context. If a context is not
  given, the value in the context* atom will be used."
  ([job]
   (enqueue @context* job))
  ([context job]
   (jobs/throw-if-invalid job)
   (let [{:keys [job-id queue] :as job} (jobs/normalize job)]
     (with-transaction [context context]
       (jobs/save context job)
       (queue/push context job-id queue))
     job-id)))

(defn run-at
  "Schedules a job to be run at a specified time. Farmhand will queue the job
  roughly when it is scheduled, but currently does not guarantee accuracy.

  The (optional) first argument is a Farmhand context. If a context is not
  given, the value in the context* atom will be used.

  The 'at' argument should be a timestamp specified in milliseconds.

  Returns the updated job."
  ([job at]
   (queue/run-at @context* job at))
  ([context job at]
   (queue/run-at context job at)))

(defn run-in
  "Schedules a job to be run at some time from now. Like run-at, the job will
  be queued roughly around the requested time.

  The (optional) first argument is a Farmhand context. If a context is not
  given, the value in the context* atom will be used.

  The 'unit' argument can be one of :milliseconds, :seconds, :minutes, :hours,
  or :days and specifies the unit of 'in'. For example, schedule a job in 2
  minutes with

    (run-in context job 2 :minutes)

  Returns the updated job."
  ([job in unit]
   (queue/run-in @context* job in unit))
  ([context job in unit]
   (queue/run-in context job in unit)))

(def registries
  [{:name queue/in-flight-registry :cleanup-fn queue/in-flight-cleanup}
   {:name queue/scheduled-registry :cleanup-fn queue/scheduled-cleanup}
   {:name queue/completed-registry :cleanup-fn jobs/delete}
   {:name queue/dead-letter-registry :cleanup-fn jobs/delete}])

(defn create-context
  "Creates a Farmhand context, which can be thought of as the Farmhand client.
  This map is used to store various options that are used throughout the code.
  Available options are:

  :queues
  :jedis-pool
  :prefix
  :handler

  More info on these options can be found in the Farmhand wiki."
  ([] (create-context {}))
  ([{:keys [handler queues redis pool prefix]}]
   (let [context (-> {:queues (config/queues queues)
                      :jedis-pool (or pool (redis/create-pool (config/redis redis)))
                      :prefix (config/prefix prefix)
                      :handler (or handler handler/default-handler)}
                     (assoc :registries registries))]
     (reset! context* context)
     context)))

(defn start-server
  "Creates a Farmhand server. If called with no arguments, will create a
  server running 2 worker threads. If no context is given, this function
  also creates one. All options passed to the function are passed directly
  to the create-context function."
  ([] (start-server {}))
  ([{:keys [context num-workers] :as opts}]
   (let [context (or context (create-context opts))
         stop-chan (async/chan)
         threads (concat
                   (for [_ (range (config/num-workers num-workers))]
                     (work/work-thread context stop-chan))
                   [(registry/cleanup-thread context stop-chan)])
         server {:context context
                 :stop-chan stop-chan
                 :threads (doall threads)}]
     (reset! server* server)
     server)))

(defn stop-server
  "Stops a running Farmhand server. If no server is given, this function will
  stop the server in the server* atom."
  ([] (stop-server @server*))
  ([{:keys [context stop-chan threads]}]
   (async/close! stop-chan)
   (async/<!! (async/merge threads))
   (redis/close-pool context)))
